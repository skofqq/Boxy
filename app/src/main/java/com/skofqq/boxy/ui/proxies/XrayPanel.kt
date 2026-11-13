package com.skofqq.boxy.ui.proxies

import android.widget.Toast
import androidx.compose.foundation.background
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
    var showLog by remember { mutableStateOf(false) }
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

    LaunchedEffect(reloadKey, reload) { setup = XrayApi.setup() }
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

    if (showLog) {
        XrayAccessLog(bottom, onBack = { showLog = false })
        return
    }

    val s = setup
    // The opened balancer sits outside the list, so scrolling never takes it away.
    val openApi = s?.api
    if (s != null && openApi != null && openBalancer != null && openBalancer in s.balancers) {
        val b = openBalancer!!
        val v = BalancerView(
            b, s.strategies[b] ?: "random", XrayApi.balancerTargets(s, b), balancerInfo[b],
            metrics?.outbounds.orEmpty().associateBy { it.tag }, s.outbounds.associate { it.tag to it.protocol }, tested?.delays.orEmpty(),
        )
        GroupDialog(
            content = { mod ->
                OpenBalancer(v, testing, onClose = { openBalancer = null }, onTest = { runTest(v.targets) }, onPick = { t ->
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
                    val views = s.balancers.map { b ->
                        BalancerView(b, s.strategies[b] ?: "random", XrayApi.balancerTargets(s, b), balancerInfo[b], states, protocols, tested?.delays.orEmpty())
                    }
                    item {
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.xray_balancers),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Boxy.colors.text,
                                )
                                MsTestButton(testing) { runTest(XrayApi.proxyTags(s)) }
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
                if (m != null) item {
                    SectionCard(stringResource(R.string.xray_outbounds), stringResource(R.string.xray_outbounds_sub)) {
                        val list = m.outbounds.sortedWith(compareBy({ it.alive != true }, { it.delayMs ?: Long.MAX_VALUE }, { it.tag }))
                        list.forEachIndexed { i, o -> OutboundRow(o, tested?.delays?.get(o.tag), i < list.size - 1) }
                    }
                }
                item {
                    SectionCard(null) {
                        Row(
                            Modifier.fillMaxWidth().clickable { showLog = true }.padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.xray_access_log), style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
                                Text(stringResource(R.string.xray_access_log_sub), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
                            }
                            Text("›", style = MaterialTheme.typography.titleLarge, color = Boxy.colors.text2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutboundRow(o: XrayApi.OutboundState, tested: Int?, divider: Boolean) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(enabled = o.error != null) { open = !open }.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when (o.alive) {
                true -> Tints.green.fg
                false -> Tints.red.fg
                null -> Tints.gray.fg
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(12.dp))
            Text(XrayApi.displayName(o.tag), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tested != null) {
                MsPill(tested, busy = false, onTest = {})
            } else {
                Text(
                    when {
                        o.alive == true && o.delayMs != null -> stringResource(R.string.unit_ms, o.delayMs.toInt())
                        o.alive == false -> stringResource(R.string.xray_dead)
                        else -> "—"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                )
            }
        }
        Text(
            "↑ ${Format.bytes(context, o.up)}   ↓ ${Format.bytes(context, o.down)}",
            Modifier.padding(start = 22.dp, top = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Boxy.colors.text2,
        )
        if (open && o.error != null) {
            Text(
                o.error,
                Modifier.padding(start = 22.dp, top = 4.dp).clip(RoundedCornerShape(10.dp)).background(Boxy.colors.surface2).padding(8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Tints.red.fg,
            )
        }
    }
    if (divider) Box(Modifier.fillMaxWidth().padding(start = 40.dp).height(1.dp).background(Boxy.colors.outline.copy(alpha = 0.3f)))
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
