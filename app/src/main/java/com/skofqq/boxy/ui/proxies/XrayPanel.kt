package com.skofqq.boxy.ui.proxies

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateMapOf
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
    // Balancer -> outbound picked here ("" = the balancer's own choice); Xray does not report the override back.
    val picked = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(reloadKey, reload) { setup = XrayApi.setup() }
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
    PinnedLazyPage(PaddingValues(bottom = bottom + 16.dp), header = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Xray", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (metrics != null) Tints.green.fg else Tints.gray.fg))
            Spacer(Modifier.weight(1f))
            if (metrics != null) {
                Text(
                    "↑ ${Format.bytes(context, speed.first)}/s  ↓ ${Format.bytes(context, speed.second)}/s",
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
                // Balancers: pin one outbound, or give the choice back to the balancer.
                val api = s.api
                if (api != null && s.apiRouting && s.balancers.isNotEmpty()) item {
                    SectionCard(stringResource(R.string.xray_balancers), stringResource(R.string.xray_balancers_sub)) {
                        s.balancers.forEach { b ->
                            Text(b, Modifier.padding(horizontal = 18.dp, vertical = 4.dp), style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val current = picked[b] ?: ""
                                PlainChip(stringResource(R.string.xray_balancer_auto), current == "") {
                                    scope.launch { if (XrayApi.overrideBalancer(api, b, null)) picked[b] = "" }
                                }
                                XrayApi.proxyTags(s).forEach { t ->
                                    PlainChip(t, current == t) {
                                        scope.launch {
                                            if (XrayApi.overrideBalancer(api, b, t)) picked[b] = t
                                            else Toast.makeText(context, R.string.op_failed, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (m != null) item {
                    SectionCard(stringResource(R.string.xray_outbounds), stringResource(R.string.xray_outbounds_sub)) {
                        val list = m.outbounds.sortedWith(compareBy({ it.alive != true }, { it.delayMs ?: Long.MAX_VALUE }, { it.tag }))
                        list.forEachIndexed { i, o -> OutboundRow(o, i < list.size - 1) }
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
private fun OutboundRow(o: XrayApi.OutboundState, divider: Boolean) {
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
            Text(o.tag, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    o.alive == true && o.delayMs != null -> "${o.delayMs} ms"
                    o.alive == false -> stringResource(R.string.xray_dead)
                    else -> "—"
                },
                style = MaterialTheme.typography.labelLarge,
                color = color,
            )
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

/** A flat chip: glass chips sample the panel's backdrop, which this panel is drawn into. */
@Composable
private fun PlainChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Boxy.colors.accent.copy(alpha = 0.22f) else Boxy.colors.surface2)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Boxy.colors.accent else Boxy.colors.text,
        maxLines = 1,
    )
}
