package com.skofqq.boxy.ui.proxies

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.Image
import com.skofqq.boxy.R
import com.skofqq.boxy.net.ClashApi
import com.skofqq.boxy.net.Net
import com.skofqq.boxy.net.ProxyGroup
import com.skofqq.boxy.net.ProxySnapshot
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.ui.components.HeaderAction
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Proxy groups of the running core (Clash API): the node each group uses, delay tests and switching nodes
 * in selector groups. Talks to the core only while open or on a tap, so it costs nothing in the background.
 */
@Composable
fun ProxiesScreen(contentPadding: PaddingValues, header: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var api by remember { mutableStateOf<ClashApi?>(null) }
    var snapshot by remember { mutableStateOf<ProxySnapshot?>(null) }
    var state by remember { mutableStateOf(LoadState.LOADING) }
    val delays = remember { mutableStateMapOf<String, Int>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    var expanded by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    suspend fun refresh() {
        val s = runCatching { BoxModule.state() }.getOrNull()
        if (s?.running != true) {
            state = LoadState.STOPPED
            return
        }
        val a = api ?: Net.clashApi(s.core).also { api = it }
        val snap = a?.let { Net.clashProxies(it) }
        snapshot = snap
        state = if (snap == null) LoadState.NO_API else LoadState.READY
    }
    LaunchedEffect(reload) { refresh() }

    fun test(group: ProxyGroup) {
        val a = api ?: return
        if (testing[group.name] == true) return
        testing[group.name] = true
        scope.launch {
            val result = Net.clashGroupDelay(a, group.name) ?: run {
                // Cores without a group test (sing-box): every member on its own, a few at a time.
                val gate = Semaphore(6)
                coroutineScope {
                    group.all.map { n -> async { gate.withPermit { n to Net.clashProxyDelay(a, n) } } }.awaitAll()
                }.mapNotNull { (n, d) -> d?.let { n to it } }.toMap()
            }
            group.all.forEach { n -> delays[n] = result[n] ?: 0 }
            testing[group.name] = false
            refresh()
        }
    }

    fun testNode(name: String) {
        val a = api ?: return
        if (testing[name] == true) return
        testing[name] = true
        scope.launch {
            delays[name] = Net.clashProxyDelay(a, name) ?: 0
            testing[name] = false
        }
    }

    fun select(group: ProxyGroup, name: String) {
        val a = api ?: return
        scope.launch { if (Net.clashSelect(a, group.name, name)) refresh() }
    }

    PinnedLazyPage(contentPadding, header = {
        header()
        SubPageHeader(
            stringResource(R.string.proxies_title),
            snapshot?.let { stringResource(R.string.proxies_sub, it.groups.size) } ?: stringResource(R.string.proxies_hint),
            null,
        ) {
            HeaderAction(BoxyIcons.Refresh, stringResource(R.string.action_refresh)) { reload++ }
        }
    }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            LoadState.LOADING -> item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            LoadState.STOPPED, LoadState.NO_API -> item {
                Text(
                    stringResource(if (state == LoadState.STOPPED) R.string.proxies_stopped else R.string.proxies_no_api),
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
                    color = Boxy.colors.text2,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            LoadState.READY -> {
                val snap = snapshot ?: return@PinnedLazyPage
                // Zashboard layout in Boxy's style: group tiles two per row; the open group takes a whole row
                // and shows its nodes as tiles too.
                val rows = mutableListOf<List<ProxyGroup>>()
                var pending = mutableListOf<ProxyGroup>()
                snap.groups.forEach { g ->
                    if (g.name == expanded) {
                        if (pending.isNotEmpty()) rows += pending
                        pending = mutableListOf()
                        rows += listOf(g)
                    } else {
                        pending += g
                        if (pending.size == 2) {
                            rows += pending
                            pending = mutableListOf()
                        }
                    }
                }
                if (pending.isNotEmpty()) rows += pending
                rows.forEach { row ->
                    item(key = row.joinToString("|") { it.name }) {
                        if (row.size == 1 && row[0].name == expanded) {
                            val g = row[0]
                            OpenGroup(
                                g, snap, delays, testing,
                                onClose = { expanded = null },
                                onTestGroup = { test(g) },
                                onTestNode = ::testNode,
                                onSelect = { select(g, it) },
                            )
                        } else {
                            Row(
                                Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                row.forEach { g ->
                                    GroupTile(
                                        g, snap, delays, testing[g.name] == true,
                                        Modifier.weight(1f).fillMaxHeight(),
                                        onOpen = { expanded = g.name },
                                        onTest = { test(g) },
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class LoadState { LOADING, STOPPED, NO_API, READY }

private fun delayOf(name: String, snap: ProxySnapshot, delays: Map<String, Int>): Int? =
    delays[name] ?: snap.nodes[name]?.delay?.takeIf { snap.group(name) == null } ?: snap.leaf(name)?.let { delays[it] ?: snap.nodes[it]?.delay }

private fun aliveText(group: ProxyGroup, snap: ProxySnapshot, delays: Map<String, Int>): String {
    val alive = group.all.count { (delayOf(it, snap, delays) ?: 0) > 0 }
    return "${group.type.uppercase()} · $alive/${group.all.size}"
}

@Composable
private fun GroupIcon(url: String?, size: Dp) {
    val context = LocalContext.current
    val img by produceState<ImageBitmap?>(null, url) { value = url?.let { ProxyIcons.load(context, it) } }
    img?.let { Image(it, null, Modifier.size(size).clip(RoundedCornerShape(50))) }
}

/** Group head: name, type with live / total nodes, its icon. */
@Composable
private fun GroupHead(group: ProxyGroup, snap: ProxySnapshot, delays: Map<String, Int>, iconSize: Dp) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.titleLarge, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                aliveText(group, snap, delays),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
                color = Boxy.colors.text2,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        GroupIcon(group.icon, iconSize)
    }
}

/** The node a group goes through, and the group's delay (tap the pill to test the whole group). */
@Composable
private fun GroupNow(group: ProxyGroup, snap: ProxySnapshot, delays: Map<String, Int>, busy: Boolean, onTest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(BoxyIcons.ArrowCircle, null, Modifier.size(18.dp), tint = Boxy.colors.text2)
        Spacer(Modifier.width(6.dp))
        Text(
            group.now ?: "—",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = Boxy.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        DelayPill(snap.leaf(group.name)?.let { delayOf(it, snap, delays) }, busy, onTest)
    }
}

@Composable
private fun GroupTile(
    group: ProxyGroup,
    snap: ProxySnapshot,
    delays: Map<String, Int>,
    busy: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onTest: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(Boxy.colors.card).clickable(onClick = onOpen).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        GroupHead(group, snap, delays, 44.dp)
        Spacer(Modifier.height(10.dp))
        GroupNow(group, snap, delays, busy, onTest)
    }
}

@Composable
private fun OpenGroup(
    group: ProxyGroup,
    snap: ProxySnapshot,
    delays: Map<String, Int>,
    testing: Map<String, Boolean>,
    onClose: () -> Unit,
    onTestGroup: () -> Unit,
    onTestNode: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Boxy.colors.card).padding(14.dp)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClose)) {
            GroupHead(group, snap, delays, 52.dp)
            Spacer(Modifier.height(10.dp))
            GroupNow(group, snap, delays, testing[group.name] == true, onTestGroup)
        }
        Spacer(Modifier.height(12.dp))
        group.all.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pair.forEach { name ->
                    NodeTile(
                        name,
                        snap,
                        delays,
                        selected = name == group.now,
                        busy = testing[name] == true || testing[group.name] == true,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = if (group.selectable) ({ onSelect(name) }) else null,
                        onTest = { onTestNode(name) },
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (!group.selectable) {
            Text(stringResource(R.string.proxies_auto_note), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
        }
    }
}

@Composable
private fun NodeTile(
    name: String,
    snap: ProxySnapshot,
    delays: Map<String, Int>,
    selected: Boolean,
    busy: Boolean,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    onTest: () -> Unit,
) {
    val node = snap.nodes[name]
    val proto = node?.let { n ->
        val transport = when {
            n.xudp -> "xudp"
            n.udp -> "udp"
            else -> null
        }
        listOfNotNull(n.type.lowercase().ifBlank { null }, transport).joinToString(" / ")
    }
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) Boxy.colors.accent.copy(alpha = 0.16f) else Boxy.colors.surface2)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Boxy.colors.accent else Boxy.colors.text,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(proto.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DelayPill(delayOf(name, snap, delays), busy, onTest)
        }
    }
}

/** Delay pill: the number in its colour, a bolt before the first test, a cross on timeout; tap to test. */
@Composable
private fun DelayPill(delay: Int?, busy: Boolean, onTest: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.page).clickable(enabled = !busy, onClick = onTest)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            delay == null -> Icon(BoxyIcons.Bolt, stringResource(R.string.proxies_test), Modifier.size(14.dp), tint = Boxy.colors.text2)
            delay <= 0 -> Text("×", style = MaterialTheme.typography.labelMedium, color = Tints.red.fg)
            else -> Text(
                delay.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    delay < 300 -> Tints.green.fg
                    delay < 800 -> Tints.amber.fg
                    else -> Tints.red.fg
                },
            )
        }
    }
}

/** Delay in ms coloured by quality; "timeout" for 0, nothing before the first test. */
@Composable
fun DelayText(delay: Int?) {
    if (delay == null) return
    val (text, color) = when {
        delay <= 0 -> stringResource(R.string.proxies_timeout) to Tints.red.fg
        delay < 300 -> stringResource(R.string.unit_ms, delay) to Tints.green.fg
        delay < 800 -> stringResource(R.string.unit_ms, delay) to Tints.amber.fg
        else -> stringResource(R.string.unit_ms, delay) to Tints.red.fg
    }
    Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
}
