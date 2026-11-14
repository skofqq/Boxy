package com.skofqq.boxy.ui.proxies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.combinedClickable
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
    /** Prefixes of the extra lists (subscription_extra_xray, e.g. "wl"): their servers are shown as one entry. */
    private val pooled: Set<String> = emptySet(),
    /** Every pooled server of the config: a group can be pinned to a list's fastest one even when it does not pick from it. */
    private val poolTags: List<String> = emptyList(),
    /** A group that goes direct by itself ("RU сайты"): no "⚡️ Fastest", its direct choice is the default. */
    val directOnly: Boolean = false,
) {
    fun isPooled(tag: String) = pooled.any { tag.startsWith("$it-") }

    /** The extra lists offered in this group, by prefix: its own pooled choices, else every list (not for a direct-only group). */
    fun pools(): Map<String, List<String>> {
        val own = targets.filter { isPooled(it) }
        val list = own.ifEmpty { if (targets.any { protocols[it] != "freedom" && !isPooled(it) }) poolTags else emptyList() }
        return list.groupBy { t -> pooled.first { t.startsWith("$it-") } }
    }

    /** The live pooled server with the lowest delay. */
    fun best(list: List<String>): String? = list.filter { (delay(it) ?: 0) > 0 }.minByOrNull { delay(it)!! }

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
        // A direct outbound named by the config ("🇷🇺 Без VPN") keeps its name; a plain "direct" reads "No VPN".
        if (protocols[tag] == "freedom" && (tag == "direct" || tag == "freedom")) stringResource(R.string.xray_no_vpn) else XrayApi.displayName(tag)
    val pinned: String? get() = info?.override?.takeIf { it.isNotEmpty() }
    /** The outbound traffic goes through now: the pinned one, else the balancer's own pick. */
    val current: String? get() = pinned ?: info?.picks
    /** Servers among the choices: not "Без VPN" (freedom), not an extra list's (White List is one choice of its own). */
    private val serverTargets get() = targets.filter { protocols[it] != "freedom" && !isPooled(it) }
    val alive get() = serverTargets.count { states[it]?.alive == true }
    val servers get() = serverTargets.size
}

private const val POOL = "\u0000pool:"

/** How an extra list reads: "⚡️ White List" for "wl", else its prefix. */
internal fun poolName(prefix: String) = if (prefix == "wl") "⚡️ White List" else "⚡️ $prefix"

/** Balancer card in the panel's grid, like a mihomo group: name, strategy with live / total, and where it goes. */
@Composable
internal fun BalancerTile(b: BalancerView, modifier: Modifier, onOpen: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { GroupIcons.load(context) }
    var editIcon by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (editIcon) GroupIconDialog(b.tag) { editIcon = false }
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(Boxy.colors.card)
            .combinedClickable(onClick = onOpen, onLongClick = { editIcon = true }).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        BalancerHead(b)
        Spacer(Modifier.height(8.dp))
        BalancerNow(b, long = false)
    }
}

@Composable
private fun BalancerHead(b: BalancerView, long: Boolean = false) {
    // Xray has no group icons: the mihomo config's for the same name, or one set by hand (long press).
    Row(verticalAlignment = Alignment.Top) {
    Column(Modifier.weight(1f)) {
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
        Spacer(Modifier.width(8.dp))
        GroupIconImage(GroupIcons.icon(b.tag), if (long) 32.dp else 38.dp)
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
        b.info?.picks != null -> if (long && !b.directOnly) "$auto → ${b.name(b.info.picks)}" else b.name(b.info.picks)
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
    /** A server's own pill tests only that server. */
    onTestOne: (String) -> Unit = { onTest() },
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
        // An extra list (a White List of hundreds of servers) is one tile: its fastest live server.
        val pools = b.pools()
        val servers = b.targets.filter { it !in direct && !b.isPooled(it) }.sortedBy { t -> b.delay(t)?.takeIf { it > 0 } ?: Int.MAX_VALUE }
        val tiles: List<String?> = (if (b.directOnly) emptyList() else listOf<String?>(null)) + direct + pools.keys.map { POOL + it } + servers
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
                    } else if (t.startsWith(POOL)) {
                        val list = pools[t.removePrefix(POOL)].orEmpty()
                        val best = b.best(list)
                        ServerTile(
                            poolName(t.removePrefix(POOL)),
                            "${list.count { (b.delay(it) ?: 0) > 0 }}/${list.size}" + (best?.let { " · " + b.name(it) } ?: ""),
                            b.delay(best) ?: if (list.any { b.delay(it) == 0 }) 0 else null,
                            selected = b.pinned != null && b.isPooled(b.pinned!!) && list.contains(b.pinned),
                            busy = busy,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onTest = { list.forEach(onTestOne) },
                        ) { if (best != null) onPick(best) }
                    } else {
                        ServerTile(
                            b.name(t),
                            b.protocol(t),
                            b.delay(t),
                            // A direct-only group's own direct choice is its default: picking it gives the choice back.
                            selected = b.pinned == t || (b.directOnly && b.pinned == null && b.protocol(t) == "freedom" && (b.info?.picks ?: t) == t),
                            busy = busy,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onTest = { onTestOne(t) },
                        ) { onPick(if (b.directOnly && b.protocol(t) == "freedom" && (b.info?.picks ?: t) == t) null else t) }
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
