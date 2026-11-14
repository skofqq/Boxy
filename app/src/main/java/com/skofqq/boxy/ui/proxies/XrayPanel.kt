package com.skofqq.boxy.ui.proxies

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.material3.toShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.net.XrayApi
import com.skofqq.boxy.ui.components.NoticeCard
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import com.skofqq.boxy.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val POLL_MS = 2000L

/**
 * Xray's panel: outbounds with observatory alive / delay and their traffic, the core's speed, balancer overrides
 * and the access log. Built on the metrics endpoint, polled only while this screen is shown.
 */
@Composable
internal fun XrayPanel(bottom: Dp, reloadKey: Int, running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var setup by remember { mutableStateOf<XrayApi.Setup?>(null) }
    var metrics by remember { mutableStateOf<XrayApi.Metrics?>(null) }
    var speed by remember { mutableStateOf(0L to 0L) }
    var withApi by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    // What each balancer is on, asked from Xray when the panel opens and after a pin (not polled).
    var balancerInfo by remember { mutableStateOf<Map<String, XrayApi.BalancerInfo>>(emptyMap()) }
    var infoKey by remember { mutableIntStateOf(0) }
    var openBalancer by remember { mutableStateOf<String?>(null) }
    // "Test": run on tap only, results kept while the panel is open.
    var tested by remember { mutableStateOf<XrayApi.Tested?>(null) }
    var testing by remember { mutableStateOf(false) }
    fun runTest(tags: List<String>) {
        if (testing || tags.isEmpty()) return
        testing = true
        scope.launch {
            val r = XrayApi.testDelays(tags)
            tested = XrayApi.Tested((tested?.delays.orEmpty()) + r.delays, r.at)
            testing = false
        }
    }

    // One server's pill: tests that server only, next to a running group test.
    fun testOne(tag: String) {
        scope.launch {
            val r = XrayApi.testDelays(listOf(tag))
            tested = XrayApi.Tested((tested?.delays.orEmpty()) + r.delays, r.at)
        }
    }

    LaunchedEffect(reloadKey, reload) { setup = XrayApi.setup() }
    // Extra lists (subscription_extra_xray, e.g. a White List): shown as one entry, not server by server.
    var pooled by remember { mutableStateOf(setOf("wl")) }
    LaunchedEffect(reloadKey) {
        pooled = runCatching { com.skofqq.boxy.configs.XraySubscription.extras().map { it.prefix }.toSet() }.getOrDefault(emptySet()) + "wl"
    }
    val apiListen = setup?.takeIf { it.apiRouting }?.api
    LaunchedEffect(apiListen, running, infoKey, setup?.balancers) {
        val s = setup
        if (apiListen == null || !running || s == null) return@LaunchedEffect
        // Right after a start the observatory has not answered yet: ask again once it had time.
        // Pins made earlier come back after a core restart (Xray forgets them).
        XrayApi.restorePins(s)
        balancerInfo = XrayApi.balancerInfo(apiListen, s.balancers)
        if (balancerInfo.values.any { it.picks == null }) {
            delay(8000)
            balancerInfo = XrayApi.balancerInfo(apiListen, s.balancers)
        }
    }
    val listen = setup?.metrics
    LaunchedEffect(listen, running) {
        if (listen == null || !running) return@LaunchedEffect
        var prev: Pair<Long, Long>? = null
        var prevAt = 0L
        while (true) {
            val m = XrayApi.metrics(listen)
            metrics = m
            if (m != null) {
                val up = m.outbounds.sumOf { it.up }
                val down = m.outbounds.sumOf { it.down }
                val now = System.nanoTime()
                prev?.let { (pu, pd) ->
                    val secs = (now - prevAt) / 1e9
                    if (secs > 0) speed = ((up - pu) / secs).toLong().coerceAtLeast(0) to ((down - pd) / secs).toLong().coerceAtLeast(0)
                }
                prev = up to down
                prevAt = now
            }
            delay(POLL_MS)
        }
    }

    val s = setup
    val poolTags = s?.outbounds.orEmpty().map { it.tag }.filter { t -> pooled.any { t.startsWith("$it-") } }
    /** The outbounds a balancer's own selector picks from (not the ones a pin may add). */
    fun own(b: String) = s?.outbounds.orEmpty().filter { o -> s?.selectors?.get(b).orEmpty().any { o.tag.startsWith(it) } }
    fun view(b: String): BalancerView {
        val mine = own(b)
        return BalancerView(
            b, s!!.strategies[b] ?: "random", XrayApi.balancerTargets(s, b), balancerInfo[b],
            metrics?.outbounds.orEmpty().associateBy { it.tag }, s.outbounds.associate { it.tag to it.protocol }, tested?.delays.orEmpty(),
            pooled, poolTags, directOnly = mine.isNotEmpty() && mine.all { it.protocol == "freedom" },
        )
    }
    // The opened balancer sits outside the list, so scrolling never takes it away.
    val openApi = s?.api
    if (s != null && openApi != null && openBalancer != null && openBalancer in s.balancers) {
        val b = openBalancer!!
        val v = view(b)
        GroupDialog(
            content = { mod ->
                OpenBalancer(v, testing, onClose = { openBalancer = null }, onTest = { runTest(v.targets.filterNot { v.isPooled(it) }) }, onTestOne = { testOne(it) }, onPick = { t ->
                    scope.launch {
                        if (XrayApi.overrideBalancer(openApi, b, t)) {
                            XrayApi.savePin(b, t)
                            infoKey++
                        }
                        else Toast.makeText(context, R.string.xray_balancer_failed, Toast.LENGTH_LONG).show()
                    }
                }, modifier = mod)
            },
            onClose = { openBalancer = null },
        )
    }
    PinnedLazyPage(PaddingValues(bottom = bottom + 16.dp), header = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Xray", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (metrics != null) Tints.green.fg else Tints.gray.fg))
            Spacer(Modifier.weight(1f))
            if (metrics != null) {
                Text(
                    "↑ ${Format.speed(context, speed.first)}  ↓ ${Format.speed(context, speed.second)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Boxy.colors.text2,
                )
            }
        }
    }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            s == null -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            !s.panelReady -> item {
                SectionCard(null) {
                    NoticeCard(stringResource(R.string.xray_panel_off_title), stringResource(R.string.xray_panel_off_body))
                    SwitchRow(BoxyIcons.Tune, stringResource(R.string.xray_panel_with_api), stringResource(R.string.xray_panel_with_api_sub), withApi, showDivider = false) { withApi = it }
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        SheetButtons(stringResource(if (busy) R.string.check_running else R.string.xray_panel_enable), {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    val r = XrayApi.enablePanel(withApi)
                                    busy = false
                                    if (r.isFailure) {
                                        Toast.makeText(context, context.getString(R.string.xray_panel_failed, r.exceptionOrNull()?.message.orEmpty()), Toast.LENGTH_LONG).show()
                                    }
                                    reload++
                                }
                            }
                        })
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            else -> {
                val m = metrics
                if (!running) item { NoticeCard(stringResource(R.string.proxies_stopped_title), stringResource(R.string.panel_core_needed), Modifier.padding(horizontal = 16.dp)) }
                else if (m == null) item { NoticeCard(stringResource(R.string.xray_panel_no_answer_title), stringResource(R.string.xray_panel_no_answer, s.metrics.orEmpty()), Modifier.padding(horizontal = 16.dp)) }
                // Balancers as group cards, two per row; a card opens the servers to pin one or give the choice back.
                val api = s.api
                if (api != null && s.apiRouting && s.balancers.isNotEmpty()) {
                    val states = m?.outbounds.orEmpty().associateBy { it.tag }
                    val protocols = s.outbounds.associate { it.tag to it.protocol }
                    // A balancer over an extra list only ("⚡️ White List") is not a group of its own here: every group
                    // offers it as one choice.
                    val views = s.balancers.filterNot { b -> own(b).let { o -> o.isNotEmpty() && o.all { x -> pooled.any { x.tag.startsWith("$it-") } } } }.map { view(it) }
                    item {
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.xray_balancers),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Boxy.colors.text,
                                )
                                MsTestButton(testing) { runTest(XrayApi.proxyTags(s).filterNot { t -> pooled.any { t.startsWith("$it-") } }) }
                            }
                            // Where the numbers come from and how fresh they are.
                            val probed = m?.outbounds?.mapNotNull { it.probedAt }?.maxOrNull()
                            val fresh = tested?.let { stringResource(R.string.xray_tested_ago, agoText(it.at)) }
                                ?: probed?.let { stringResource(R.string.xray_probed_ago, agoText(it)) }
                            if (fresh != null) {
                                Text(fresh, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                            }
                        }
                    }
                    views.chunked(2).forEach { pair ->
                        item(key = "bal-" + pair.first().tag) {
                            Row(
                                Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                pair.forEach { v -> BalancerTile(v, Modifier.weight(1f).fillMaxHeight()) { openBalancer = v.tag } }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (m != null) {
                    item {
                        Text(
                            stringResource(R.string.xray_outbounds),
                            Modifier.padding(start = 20.dp, top = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = Boxy.colors.text,
                        )
                    }
                    outboundCards(m, tested?.delays.orEmpty(), pooled, s.outbounds.associate { it.tag to it.protocol }) { testOne(it) }
                }
            }
        }
    }
}

/** Splits a server name into its leading flag (two regional-indicator letters) and the rest. */
private fun flagOf(name: String): Pair<String?, String> {
    if (name.length >= 4 && name.codePointAt(0) in 0x1F1E6..0x1F1FF) {
        val second = Character.charCount(name.codePointAt(0))
        if (name.codePointAt(second) in 0x1F1E6..0x1F1FF) {
            val end = second + Character.charCount(name.codePointAt(second))
            return name.substring(0, end) to name.substring(end).trim()
        }
    }
    return null to name
}

/**
 * Xray's outbounds, Material 3 Expressive: a card per server with its flag on a shape that shows the state (a
 * cookie when it answers, a burst when it is down), its delay pill (tap: test this server) and traffic; an extra
 * list (White List) as one card; Xray's own outbounds (direct, block, dns) as chips.
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.outboundCards(
    m: XrayApi.Metrics,
    tested: Map<String, Int>,
    pooled: Set<String>,
    protocols: Map<String, String>,
    onTest: (String) -> Unit,
) {
    val isPooled = { t: String -> pooled.any { t.startsWith("$it-") } }
    val system = setOf("freedom", "blackhole", "dns", "loopback")
    val servers = m.outbounds.filter { !isPooled(it.tag) && protocols[it.tag] !in system }
        .sortedWith(compareBy({ it.alive != true }, { it.delayMs ?: Long.MAX_VALUE }, { it.tag }))
    val pools = m.outbounds.filter { isPooled(it.tag) }.groupBy { o -> pooled.first { o.tag.startsWith("$it-") } }
    val own = m.outbounds.filter { !isPooled(it.tag) && protocols[it.tag] in system }
    servers.forEach { o -> item(key = "ob-" + o.tag) { ServerCard(o, protocols[o.tag].orEmpty(), tested[o.tag]) { onTest(o.tag) } } }
    pools.forEach { (prefix, items) -> item(key = "pool-$prefix") { PoolCard(prefix, items) } }
    if (own.isNotEmpty()) item(key = "ob-system") {
        val context = LocalContext.current
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            own.forEach { o ->
                Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Boxy.colors.card).padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text(
                        if (protocols[o.tag] == "freedom" && o.tag == "direct") stringResource(R.string.xray_no_vpn) else XrayApi.displayName(o.tag),
                        style = MaterialTheme.typography.labelLarge,
                        color = Boxy.colors.text,
                        maxLines = 1,
                    )
                    Text("↑ ${Format.bytes(context, o.up)}  ↓ ${Format.bytes(context, o.down)}", style = MaterialTheme.typography.labelSmall, color = Boxy.colors.text2, maxLines = 1)
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StateShape(alive: Boolean?, content: @Composable () -> Unit) {
    val shape = when (alive) {
        true -> androidx.compose.material3.MaterialShapes.Cookie9Sided
        false -> androidx.compose.material3.MaterialShapes.Burst
        null -> androidx.compose.material3.MaterialShapes.Circle
    }
    val tint = when (alive) {
        true -> Tints.green
        false -> Tints.red
        null -> Tints.gray
    }
    Box(Modifier.size(46.dp).clip(shape.toShape()).background(tint.bg), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ServerCard(o: XrayApi.OutboundState, protocol: String, tested: Int?, onTest: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val (flag, rest) = flagOf(XrayApi.displayName(o.tag))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)).background(Boxy.colors.card)
            .clickable(enabled = o.error != null) { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateShape(o.alive) {
                if (flag != null) Text(flag, fontSize = 20.sp)
                else androidx.compose.material3.Icon(BoxyIcons.Dns, null, Modifier.size(20.dp), tint = Boxy.colors.text)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(rest.ifBlank { o.tag }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(protocol.uppercase(), "↑ ${Format.bytes(context, o.up)}  ↓ ${Format.bytes(context, o.down)}").filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Boxy.colors.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            val delay = tested ?: when (o.alive) {
                true -> o.delayMs?.toInt()?.coerceAtLeast(1)
                false -> 0
                null -> null
            }
            MsPill(delay, busy = false, onTest = onTest)
        }
        if (open && o.error != null) {
            Text(
                o.error,
                Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Boxy.colors.surface2).padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Tints.red.fg,
            )
        }
    }
}

@Composable
private fun PoolCard(prefix: String, items: List<XrayApi.OutboundState>) {
    val context = LocalContext.current
    val live = items.filter { it.alive == true }
    val best = live.minByOrNull { it.delayMs ?: Long.MAX_VALUE }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)).background(Boxy.colors.card).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateShape(if (live.isNotEmpty()) true else if (items.any { it.alive == false }) false else null) {
            androidx.compose.material3.Icon(BoxyIcons.Bolt, null, Modifier.size(22.dp), tint = Boxy.colors.text)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(poolName(prefix), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text, maxLines = 1)
            Text(
                listOfNotNull(
                    stringResource(R.string.xray_pool_alive, live.size, items.size),
                    best?.let { XrayApi.displayName(it.tag) },
                    "↑ ${Format.bytes(context, items.sumOf { it.up })}  ↓ ${Format.bytes(context, items.sumOf { it.down })}",
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = Boxy.colors.text2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        MsPill(best?.delayMs?.toInt()?.coerceAtLeast(1) ?: if (items.any { it.alive == false }) 0 else null, busy = false, onTest = {})
    }
}

/** "Test" next to the balancers' title: a spinner while it runs. */
@Composable
private fun MsTestButton(busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.card).clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        else androidx.compose.material3.Icon(BoxyIcons.Bolt, null, Modifier.size(14.dp), tint = Boxy.colors.accent)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.proxies_test), style = MaterialTheme.typography.labelLarge, color = Boxy.colors.accent)
    }
}
