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
import androidx.compose.animation.core.animateFloat
import androidx.compose.material3.toShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
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
fun ProxiesScreen(contentPadding: PaddingValues, header: @Composable () -> Unit, embedded: Boolean = false, reloadKey: Int = 0) {
    val scope = rememberCoroutineScope()
    var api by remember { mutableStateOf<ClashApi?>(null) }
    var snapshot by remember { mutableStateOf<ProxySnapshot?>(null) }
    var state by remember { mutableStateOf(LoadState.LOADING) }
    val delays = remember { mutableStateMapOf<String, Int>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    var expanded by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var starting by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
    LaunchedEffect(reload, reloadKey) { refresh() }

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

    fun start() {
        if (starting) return
        starting = true
        scope.launch {
            com.skofqq.boxy.service.BoxControl.start(context.applicationContext)
            // The core opens its API a moment after it starts.
            repeat(8) {
                refresh()
                if (state == LoadState.READY) return@repeat
                kotlinx.coroutines.delay(1000)
            }
            starting = false
        }
    }

    fun select(group: ProxyGroup, name: String) {
        val a = api ?: return
        scope.launch { if (Net.clashSelect(a, group.name, name)) refresh() }
    }

    val openGroup = snapshot?.group(expanded ?: "")
    if (openGroup != null) {
        val snap = snapshot!!
        GroupDialog(
            content = { m ->
                OpenGroup(
                    openGroup, snap, delays, testing,
                    onClose = { expanded = null },
                    onTestGroup = { test(openGroup) },
                    onTestNode = ::testNode,
                    onSelect = { select(openGroup, it) },
                    modifier = m,
                )
            },
            onClose = { expanded = null },
        )
    }

    PinnedLazyPage(contentPadding, header = {
        header()
        if (!embedded) SubPageHeader(
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
                val stopped = state == LoadState.STOPPED
                EmptyState(
                    Modifier.fillParentMaxHeight(0.82f),
                    icon = if (stopped) BoxyIcons.Power else BoxyIcons.Core,
                    title = stringResource(if (stopped) R.string.proxies_stopped_title else R.string.proxies_no_api_title),
                    body = stringResource(if (stopped) R.string.proxies_stopped else R.string.proxies_no_api),
                    action = if (stopped) stringResource(R.string.action_start) else stringResource(R.string.action_refresh),
                    busy = starting,
                    onAction = { if (stopped) start() else reload++ },
                )
            }
            LoadState.READY -> {
                val snap = snapshot ?: return@PinnedLazyPage
                // Zashboard layout in Boxy's style: group tiles two per row; an opened group pops up over the page.
                val rows = snap.groups.chunked(2)
                rows.forEach { row ->
                    item(key = row.joinToString("|") { it.name }) {
                        run {
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

/**
 * Material 3 Expressive empty state: a slowly turning cookie shape with the symbol, a title, a line of help
 * and one big action (start the service / try again). While the action runs the shape becomes the
 * shape-morphing loading indicator.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyState(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    action: String,
    busy: Boolean,
    onAction: () -> Unit,
) {
    val turn by androidx.compose.animation.core.rememberInfiniteTransition(label = "empty").animateFloat(
        0f,
        360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(24_000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "turn",
    )
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(150.dp).graphicsLayer { scaleX = appear.value; scaleY = appear.value }, contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedContent(busy, label = "empty-shape") { b ->
                Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                    if (b) {
                        androidx.compose.material3.LoadingIndicator(Modifier.size(120.dp), color = Boxy.colors.accent)
                    } else {
                        Box(
                            Modifier.size(140.dp).graphicsLayer { rotationZ = turn }
                                .clip(androidx.compose.material3.MaterialShapes.Cookie12Sided.toShape())
                                .background(Boxy.colors.accent.copy(alpha = 0.16f)),
                        )
                        Box(
                            Modifier.size(72.dp).clip(androidx.compose.material3.MaterialShapes.Cookie4Sided.toShape())
                                .background(Boxy.colors.accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, null, Modifier.size(34.dp), tint = Color.White)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(if (busy) Boxy.colors.accent.copy(alpha = 0.5f) else Boxy.colors.accent)
                .clickable(enabled = !busy, onClick = onAction)
                .padding(horizontal = 28.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text(action, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The opened group as a card over the page, like Zashboard's: the grid stays where it is, the rest of the
 * screen is dimmed (and blurred with liquid glass); a tap outside or Back closes it.
 */
@Composable
private fun GroupDialog(content: @Composable (Modifier) -> Unit, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val frosted = com.skofqq.boxy.ui.components.rememberFrostedDialog()
        val appear = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 520f)) }
        val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.86f).dp
        Box(
            Modifier.fillMaxSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            content(
                Modifier
                    .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = 14.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .graphicsLayer {
                        val v = appear.value
                        scaleX = 0.9f + 0.1f * v
                        scaleY = 0.9f + 0.1f * v
                        alpha = v.coerceIn(0f, 1f)
                    }
                    .then(with(com.skofqq.boxy.ui.components.GlassCardScope) { Modifier.card(frosted) })
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            )
        }
    }
}

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
            Text(group.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                aliveText(group, snap, delays),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 1.sp),
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
        Icon(BoxyIcons.ArrowCircle, null, Modifier.size(16.dp), tint = Boxy.colors.text2)
        Spacer(Modifier.width(6.dp))
        Text(
            group.now ?: "—",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
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
        GroupHead(group, snap, delays, 38.dp)
        Spacer(Modifier.height(8.dp))
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
    modifier: Modifier,
) {
    Column(modifier) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClose)) {
            GroupHead(group, snap, delays, 46.dp)
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
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = if (selected) Boxy.colors.accent else Boxy.colors.text,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(proto.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = Boxy.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DelayPill(delayOf(name, snap, delays), busy, onTest)
        }
    }
}

/** Delay pill: the number in its colour, a bolt before the first test, a cross on timeout; tap to test. */
@Composable
private fun DelayPill(delay: Int?, busy: Boolean, onTest: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.page).clickable(enabled = !busy, onClick = onTest)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            delay == null -> Icon(BoxyIcons.Bolt, stringResource(R.string.proxies_test), Modifier.size(14.dp), tint = Boxy.colors.text2)
            delay <= 0 -> Text("×", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = Tints.red.fg)
            else -> Text(
                delay.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
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
