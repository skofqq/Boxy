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
) {
    /** Delay as the mihomo panel shows it: ms when alive, 0 (a cross) when dead, null before the first probe. */
    fun delay(tag: String?): Int? {
        val s = tag?.let { states[it] } ?: return null
        return when (s.alive) {
            true -> s.delayMs?.toInt()?.coerceAtLeast(1)
            false -> 0
            null -> null
        }
    }

    fun protocol(tag: String) = protocols[tag].orEmpty()
    val pinned: String? get() = info?.override?.takeIf { it.isNotEmpty() }
    /** The outbound traffic goes through now: the pinned one, else the balancer's own pick. */
    val current: String? get() = pinned ?: info?.picks
    val alive get() = targets.count { states[it]?.alive == true }
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
private fun BalancerHead(b: BalancerView) {
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
            "${b.strategy.uppercase()} · ${b.alive}/${b.targets.size}",
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
private fun BalancerNow(b: BalancerView, long: Boolean) {
    val auto = stringResource(R.string.xray_balancer_auto)
    val text = when {
        b.pinned != null -> XrayApi.displayName(b.pinned!!)
        b.info?.picks != null -> if (long) "$auto → ${XrayApi.displayName(b.info.picks)}" else XrayApi.displayName(b.info.picks)
        else -> auto
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (b.pinned != null) BoxyIcons.ArrowCircle else BoxyIcons.Bolt, null, Modifier.size(16.dp), tint = Boxy.colors.text2)
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = Boxy.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        DelayPill(b.delay(b.current), busy = false, onTest = {})
    }
}

/** The opened balancer: "Auto" first, then every outbound it picks from, two per row; the current one is marked. */
@Composable
internal fun OpenBalancer(b: BalancerView, onClose: () -> Unit, onPick: (String?) -> Unit, modifier: Modifier) {
    Column(modifier) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClose)) {
            BalancerHead(b)
            Spacer(Modifier.height(10.dp))
            BalancerNow(b, long = true)
        }
        Spacer(Modifier.height(12.dp))
        val auto = stringResource(R.string.xray_balancer_auto)
        val tiles: List<String?> = listOf<String?>(null) + b.targets
        tiles.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pair.forEach { t ->
                    if (t == null) {
                        ServerTile(
                            auto,
                            b.info?.picks?.let { XrayApi.displayName(it) }.orEmpty(),
                            b.delay(b.info?.picks),
                            selected = b.pinned == null,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) { onPick(null) }
                    } else {
                        ServerTile(
                            XrayApi.displayName(t),
                            b.protocol(t),
                            b.delay(t),
                            selected = b.pinned == t,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) { onPick(t) }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(stringResource(R.string.xray_balancers_sub), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
    }
}

@Composable
private fun ServerTile(name: String, sub: String, delay: Int?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
            DelayPill(delay, busy = false, onTest = {})
        }
    }
}
