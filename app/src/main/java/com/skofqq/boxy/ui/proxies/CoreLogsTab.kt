package com.skofqq.boxy.ui.proxies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skofqq.boxy.R
import com.skofqq.boxy.net.CoreApi
import com.skofqq.boxy.net.CoreLogLine
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tint
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.delay

private val LEVELS = listOf("debug", "info", "warning", "error")

/** Lines kept on screen, newest first. */
private const val LINES_MAX = 1000

/**
 * Zashboard's Logs: the core's own log as it is written (/logs), from the chosen level up, newest on top,
 * with search, pause and clear. Lines are only collected while the tab is open.
 */
@Composable
internal fun CoreLogsTab(bottom: Dp, reloadKey: Int) {
    val core = rememberCoreApi(reloadKey)
    val api = core.api
    if (core.checked && api == null) return NoCore(bottom)
    var level by rememberSaveable { mutableStateOf("info") }
    var paused by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<CoreLogLine>() }

    LaunchedEffect(api, level, paused) {
        if (api == null || paused) return@LaunchedEffect
        // The stream ends when the core restarts; follow it again.
        while (true) {
            runCatching {
                CoreApi.logs(api, level).collect { line ->
                    lines.add(0, line)
                    if (lines.size > LINES_MAX) lines.removeRange(LINES_MAX, lines.size)
                }
            }
            delay(2000)
        }
    }

    val q = query.trim()
    val shown = if (q.isEmpty()) lines.toList() else lines.filter { it.text.contains(q, true) || it.level.contains(q, true) }

    PanelTabPage(
        bottom,
        bar = {
            PageBar(listOf(stringResource(R.string.core_logs_title) to lines.size), 0, {}) {
                MenuChip(levelLabel(level), level != "info", LEVELS.map { it to levelLabel(it) }, level) { level = it }
            }
        },
        query = query,
        onQuery = { query = it },
        hint = stringResource(R.string.core_logs_search),
        actions = {
            GlassAction(
                if (paused) BoxyIcons.Play else BoxyIcons.Pause,
                stringResource(if (paused) R.string.conn_resume else R.string.conn_pause),
            ) { paused = !paused }
            GlassAction(BoxyIcons.Delete, stringResource(R.string.core_logs_clear), enabled = lines.isNotEmpty()) { lines.clear() }
        },
    ) {
        if (shown.isEmpty()) item { EmptyLine(stringResource(if (q.isEmpty()) R.string.core_logs_empty else R.string.proxies_nothing_found)) }
        itemsIndexed(shown) { _, l -> LogCard(l, Modifier.padding(horizontal = 16.dp).fillMaxWidth()) }
    }
}

@Composable
private fun levelLabel(level: String): String = stringResource(
    when (level) {
        "debug" -> R.string.core_logs_debug
        "warning" -> R.string.core_logs_warning
        "error" -> R.string.core_logs_error
        else -> R.string.core_logs_info
    },
)

@Composable
private fun levelTint(level: String): Tint = when (level.lowercase()) {
    "error" -> Tints.red
    "warning" -> Tints.amber
    "debug" -> Tints.gray
    else -> Tints.blue
}

private val TIME = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)

@Composable
private fun LogCard(l: CoreLogLine, modifier: Modifier) {
    val tint = levelTint(l.level)
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Boxy.colors.card).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                l.level.uppercase(),
                Modifier.clip(RoundedCornerShape(50)).background(tint.bg).padding(horizontal = 8.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = tint.fg,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(TIME.format(java.util.Date(l.time)), style = MaterialTheme.typography.labelSmall, color = Boxy.colors.text2)
        }
        Text(l.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp), color = Boxy.colors.text)
    }
}
