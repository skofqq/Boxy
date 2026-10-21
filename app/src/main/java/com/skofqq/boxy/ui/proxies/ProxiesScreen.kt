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
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
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
        // Open the first group the first time, so the page is useful right away.
        if (snap != null && expanded.isEmpty()) snap.groups.firstOrNull()?.let { expanded[it.name] = true }
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
                snap.groups.forEach { g ->
                    item(key = g.name) {
                        GroupCard(
                            g,
                            snap,
                            delays,
                            open = expanded[g.name] == true,
                            busy = testing[g.name] == true,
                            onToggle = { expanded[g.name] = expanded[g.name] != true },
                            onTest = { test(g) },
                            onSelect = { select(g, it) },
                        )
                    }
                }
            }
        }
    }
}

private enum class LoadState { LOADING, STOPPED, NO_API, READY }

@Composable
private fun GroupCard(
    group: ProxyGroup,
    snap: ProxySnapshot,
    delays: Map<String, Int>,
    open: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val leaf = snap.leaf(group.name)
    fun delayOf(name: String): Int? = delays[name] ?: snap.nodes[name]?.delay ?: snap.leaf(name)?.let { delays[it] ?: snap.nodes[it]?.delay }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Boxy.colors.card)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(stringResource(typeLabel(group.type)), leaf).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Boxy.colors.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            leaf?.let { DelayText(delayOf(it)) }
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable(enabled = !busy, onClick = onTest), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(BoxyIcons.Speed, stringResource(R.string.proxies_test), Modifier.size(20.dp), tint = Boxy.colors.accent)
                }
            }
            val turn by animateFloatAsState(if (open) 180f else 0f, spring(stiffness = 500f), label = "chevron")
            Icon(BoxyIcons.ExpandMore, null, Modifier.size(22.dp).rotate(turn), tint = Boxy.colors.text2)
        }
        AnimatedVisibility(open, enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(bottom = 8.dp)) {
                group.all.forEach { name ->
                    val current = name == group.now
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (group.selectable) Modifier.clickable { onSelect(name) } else Modifier)
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(8.dp).clip(RoundedCornerShape(50))
                                .background(if (current) Boxy.colors.accent else Color.Transparent),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (current) Boxy.colors.accent else Boxy.colors.text,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            snap.nodes[name]?.type?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 1)
                            }
                        }
                        DelayText(delayOf(name))
                    }
                }
                if (!group.selectable) {
                    Text(
                        stringResource(R.string.proxies_auto_note),
                        Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Boxy.colors.text2,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
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

private fun typeLabel(type: String): Int = when (type) {
    "Selector" -> R.string.proxies_type_select
    "URLTest" -> R.string.proxies_type_auto
    "Fallback" -> R.string.proxies_type_fallback
    "LoadBalance" -> R.string.proxies_type_balance
    else -> R.string.proxies_type_other
}
