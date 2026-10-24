package com.skofqq.boxy.ui.proxies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.net.CoreApi
import com.skofqq.boxy.net.CoreConn
import com.skofqq.boxy.ui.components.SmallIconButton
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import com.skofqq.boxy.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SORTS = listOf("time", "download", "upload", "speed", "host")

/** Closed connections kept for the Closed list, newest first. */
private const val CLOSED_MAX = 300

/**
 * Zashboard's Connections: live connections of the core (polled every second while the tab is open),
 * the ones closed since, sorting, search, pause and closing one or all.
 */
@Composable
internal fun ConnectionsTab(bottom: Dp, reloadKey: Int) {
    val core = rememberCoreApi(reloadKey)
    val api = core.api
    if (core.checked && api == null) return NoCore(bottom)
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf<List<CoreConn>>(emptyList()) }
    val closed = remember { mutableStateListOf<CoreConn>() }
    val speed = remember { mutableStateMapOf<String, Long>() }
    var closedTab by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("time") }
    var paused by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val opened = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(api, paused) {
        if (api == null || paused) return@LaunchedEffect
        var last = active.associateBy { it.id }
        while (true) {
            val now = CoreApi.connections(api)
            if (now != null) {
                val ids = now.mapTo(HashSet()) { it.id }
                val gone = last.values.filter { it.id !in ids }
                if (gone.isNotEmpty()) {
                    closed.addAll(0, gone)
                    if (closed.size > CLOSED_MAX) closed.removeRange(CLOSED_MAX, closed.size)
                }
                now.forEach { c -> speed[c.id] = (c.download - (last[c.id]?.download ?: c.download)).coerceAtLeast(0) }
                gone.forEach { speed.remove(it.id) }
                active = now
                last = now.associateBy { it.id }
            }
            delay(1000)
        }
    }

    fun close(id: String?) {
        val a = api ?: return
        scope.launch {
            CoreApi.close(a, id)
            // The next poll moves it to Closed.
        }
    }

    val q = query.trim()
    val list = (if (closedTab) closed else active)
        .filter { c ->
            q.isEmpty() || c.target.contains(q, true) || c.chain.any { it.contains(q, true) } ||
                c.rule.contains(q, true) || c.process?.contains(q, true) == true || c.inbound.contains(q, true)
        }
        .let { l ->
            when (sort) {
                "download" -> l.sortedByDescending { it.download }
                "upload" -> l.sortedByDescending { it.upload }
                "speed" -> l.sortedByDescending { speed[it.id] ?: 0 }
                "host" -> l.sortedBy { it.target.lowercase() }
                else -> if (closedTab) l else l.sortedByDescending { it.start }
            }
        }
    val sortLabels = SORTS.map { it to sortLabel(it) }

    PanelTabPage(
        bottom,
        bar = {
            PageBar(
                listOf(stringResource(R.string.conn_active) to active.size, stringResource(R.string.conn_closed) to closed.size),
                if (closedTab) 1 else 0,
                { closedTab = it == 1 },
            ) {
                MenuChip(sortLabel(sort), false, sortLabels, sort) { sort = it }
            }
        },
        query = query,
        onQuery = { query = it },
        hint = stringResource(R.string.conn_search),
        actions = {
            GlassAction(
                if (paused) BoxyIcons.Play else BoxyIcons.Pause,
                stringResource(if (paused) R.string.conn_resume else R.string.conn_pause),
            ) { paused = !paused }
            if (closedTab) {
                GlassAction(BoxyIcons.Delete, stringResource(R.string.conn_clear_closed), enabled = closed.isNotEmpty()) { closed.clear() }
            } else {
                GlassAction(BoxyIcons.Close, stringResource(R.string.conn_close_all), enabled = active.isNotEmpty()) { close(null) }
            }
        },
    ) {
        if (list.isEmpty()) item { EmptyLine(stringResource(if (q.isEmpty()) R.string.conn_empty else R.string.proxies_nothing_found)) }
        items(list, key = { it.id }) { c ->
            ConnectionCard(
                c,
                speed = if (closedTab) null else speed[c.id] ?: 0,
                expanded = opened[c.id] == true,
                onToggle = { opened[c.id] = opened[c.id] != true },
                onClose = if (closedTab) null else ({ close(c.id) }),
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().animateItem(),
            )
        }
    }
}

@Composable
private fun sortLabel(key: String): String = stringResource(
    when (key) {
        "download" -> R.string.conn_sort_download
        "upload" -> R.string.conn_sort_upload
        "speed" -> R.string.conn_sort_speed
        "host" -> R.string.conn_sort_host
        else -> R.string.conn_sort_time
    },
)

/**
 * A connection as Zashboard shows it: host and age; inbound, network and traffic; the group → node it goes
 * through and its download speed. A tap shows the rule that matched and the app behind it.
 */
@Composable
private fun ConnectionCard(c: CoreConn, speed: Long?, expanded: Boolean, onToggle: () -> Unit, onClose: (() -> Unit)?, modifier: Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val small = MaterialTheme.typography.bodySmall
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Boxy.colors.card).clickable(onClick = onToggle).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.target, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(timeAgo(c.start, locale), style = small, color = Boxy.colors.text2, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf(c.inbound, c.network).filter { it.isNotBlank() }.joinToString(" | "),
                Modifier.weight(1f),
                style = small,
                color = Boxy.colors.text2,
                maxLines = 1,
            )
            Text("↓ ${Format.bytes(context, c.download)}", style = small, color = Tints.green.fg)
            Spacer(Modifier.width(8.dp))
            Text("↑ ${Format.bytes(context, c.upload)}", style = small, color = Tints.blue.fg)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                c.chain.forEachIndexed { i, name ->
                    if (i > 0) Icon(BoxyIcons.ArrowCircle, null, Modifier.padding(horizontal = 4.dp).size(14.dp), tint = Boxy.colors.text2)
                    Text(
                        name,
                        Modifier.weight(1f, fill = false),
                        style = small,
                        color = Boxy.colors.text,
                        fontWeight = if (i == c.chain.lastIndex) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (speed != null) {
                Spacer(Modifier.width(8.dp))
                Text("↓ ${Format.speed(context, speed)}", style = small, color = Tints.green.fg, maxLines = 1)
            }
            if (onClose != null) {
                Spacer(Modifier.width(6.dp))
                SmallIconButton(BoxyIcons.Close, stringResource(R.string.conn_close), onClick = onClose)
            } else {
                Spacer(Modifier.width(6.dp))
            }
        }
        if (expanded) {
            Text(stringResource(R.string.conn_rule, c.rule.ifBlank { "-" }), style = small, color = Boxy.colors.text2)
            c.process?.let { Text(stringResource(R.string.conn_app, it), style = small, color = Boxy.colors.text2) }
        }
    }
}
