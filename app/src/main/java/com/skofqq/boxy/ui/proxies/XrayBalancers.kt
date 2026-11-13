package com.skofqq.boxy.ui.proxies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skofqq.boxy.R
import com.skofqq.boxy.net.XrayApi
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons

/** What one balancer card needs: its outbounds with their observatory state and what Xray says it is on. */
internal class BalancerView(
    val tag: String,
    val strategy: String,
    val targets: List<String>,
    val info: XrayApi.BalancerInfo?,
    private val states: Map<String, XrayApi.OutboundState>,
    private val protocols: Map<String, String>,
    /** Results of the last "Test" (ms over an open connection, 0 = failed); they win over the observatory's. */
    private val tested: Map<String, Int> = emptyMap(),
) {
    /** Delay as the mihomo panel shows it: ms when alive, 0 (a cross) when dead, null before the first probe. */
    fun delay(tag: String?): Int? {
        tag?.let { tested[it] }?.let { return it }
        val s = tag?.let { states[it] } ?: return null
        return when (s.alive) {
            true -> s.delayMs?.toInt()?.coerceAtLeast(1)
            false -> 0
            null -> null
        }
    }

    fun protocol(tag: String) = protocols[tag].orEmpty()

    /** How a tag reads: "No VPN" for a direct (freedom) outbound, else the name without the subscription prefix. */
    @Composable
    fun name(tag: String): String =
        if (protocols[tag] == "freedom") stringResource(R.string.xray_no_vpn) else XrayApi.displayName(tag)
    val pinned: String? get() = info?.override?.takeIf { it.isNotEmpty() }
    /** The outbound traffic goes through now: the pinned one, else the balancer's own pick. */
    val current: String? get() = pinned ?: info?.picks
    val alive get() = targets.count { states[it]?.alive == true }
    /** Servers among the choices: "No VPN" (freedom) is not one. */
    val servers get() = targets.count { protocols[it] != "freedom" }
}

/** Balancer card in the panel's grid, like a mihomo group: name, strategy with live / total, and where it goes. */
@Composable
internal fun BalancerTile(b: BalancerView, modifier: Modifier, onOpen: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(Boxy.colors.card).clickable(onClick = onOpen).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        BalancerHead(b)
        Spacer(Modifier.height(8.dp))
        BalancerNow(b, long = false)
    }
}

@Composable
private fun BalancerHead(b: BalancerView, long: Boolean = false) {
    Column {
        Text(
            b.tag,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = Boxy.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            // Auto or pinned, then live / all servers ("No VPN" not counted); the strategy when opened.
            listOfNotNull(
                stringResource(if (b.pinned != null) R.string.xray_mode_pinned else R.string.xray_mode_auto),
                "${b.alive}/${b.servers}",
                b.strategy.uppercase().takeIf { long },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 1.sp),
            color = Boxy.colors.text2,
            maxLines = 1,
        )
    }
}

/**
 * The pinned server, or the balancer's own pick: with a bolt in a card ("⚡ Finland"), spelled out in the opened
 * balancer ("Auto → Finland"), with its delay.
 */
@Composable
private fun BalancerNow(b: BalancerView, long: Boolean, busy: Boolean = false, onTest: () -> Unit = {}) {
    val auto = stringResource(R.string.xray_balancer_auto)
    val text = when {
        b.pinned != null -> b.name(b.pinned!!)
        b.info?.picks != null -> if (long) "$auto → ${b.name(b.info.picks)}" else b.name(b.info.picks)
        else -> auto
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // In a card the server name needs the room: the pin mark only, "Auto →" is spelled out when opened.
        if (b.pinned != null || long) {
            Icon(if (b.pinned != null) BoxyIcons.ArrowCircle else BoxyIcons.Bolt, null, Modifier.size(16.dp), tint = Boxy.colors.text2)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = Boxy.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        MsPill(b.delay(b.current), busy, onTest)
    }
}

/** The opened balancer: "Auto" first, then every outbound it picks from, two per row; the current one is marked. */
@Composable
internal fun OpenBalancer(
    b: BalancerView,
    busy: Boolean,
    onClose: () -> Unit,
    onTest: () -> Unit,
    onPick: (String?) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClose)) {
            BalancerHead(b, long = true)
            Spacer(Modifier.height(10.dp))
            BalancerNow(b, long = true, busy = busy, onTest = onTest)
        }
        Spacer(Modifier.height(12.dp))
        val auto = stringResource(R.string.xray_balancer_auto)
        // Auto, "No VPN", then the live servers by delay and the dead / untested ones last (lists can be long).
        val direct = b.targets.filter { b.protocol(it) == "freedom" }
        val servers = b.targets.filter { it !in direct }.sortedBy { t -> b.delay(t)?.takeIf { it > 0 } ?: Int.MAX_VALUE }
        val tiles: List<String?> = listOf<String?>(null) + direct + servers
        tiles.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pair.forEach { t ->
                    if (t == null) {
                        ServerTile(
                            auto,
                            b.info?.picks?.let { b.name(it) }.orEmpty(),
                            b.delay(b.info?.picks),
                            selected = b.pinned == null,
                            busy = busy,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onTest = onTest,
                        ) { onPick(null) }
                    } else {
                        ServerTile(
                            b.name(t),
                            b.protocol(t),
                            b.delay(t),
                            selected = b.pinned == t,
                            busy = busy,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onTest = onTest,
                        ) { onPick(t) }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(
            stringResource(R.string.xray_balancers_sub) + "\n" + stringResource(R.string.xray_delay_note),
            style = MaterialTheme.typography.bodySmall,
            color = Boxy.colors.text2,
        )
    }
}

@Composable
private fun ServerTile(
    name: String,
    sub: String,
    delay: Int?,
    selected: Boolean,
    busy: Boolean,
    modifier: Modifier,
    onTest: () -> Unit,
    onClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) Boxy.colors.accent.copy(alpha = 0.16f) else Boxy.colors.surface2)
            .clickable(onClick = onClick)
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
            Text(sub, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = Boxy.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MsPill(delay, busy, onTest)
        }
    }
}

/** Delay pill with its unit, coloured like the mihomo panel's; a bolt before the first result, tap to test. */
@Composable
internal fun MsPill(delay: Int?, busy: Boolean, onTest: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.page).clickable(enabled = !busy, onClick = onTest)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            delay == null -> Icon(BoxyIcons.Bolt, stringResource(R.string.proxies_test), Modifier.size(14.dp), tint = Boxy.colors.text2)
            delay <= 0 -> Text("×", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = com.skofqq.boxy.ui.theme.Tints.red.fg)
            else -> Text(
                stringResource(R.string.unit_ms, delay),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = when {
                    delay < 300 -> com.skofqq.boxy.ui.theme.Tints.green.fg
                    delay < 800 -> com.skofqq.boxy.ui.theme.Tints.amber.fg
                    else -> com.skofqq.boxy.ui.theme.Tints.red.fg
                },
            )
        }
    }
}

/** "just now" / "5 min ago" */
@Composable
internal fun agoText(at: Long): String {
    val min = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
    return if (min < 1) stringResource(R.string.xray_ago_now) else stringResource(R.string.xray_ago_min, min.toInt())
}
