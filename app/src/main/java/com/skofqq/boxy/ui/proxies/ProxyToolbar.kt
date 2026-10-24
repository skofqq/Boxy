package com.skofqq.boxy.ui.proxies

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skofqq.boxy.R
import com.skofqq.boxy.net.ProxyProvider
import com.skofqq.boxy.net.ProxySnapshot
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.GlassChip
import com.skofqq.boxy.ui.components.GlassMenu
import com.skofqq.boxy.ui.components.GlassMenuItem
import com.skofqq.boxy.ui.components.SmallIconButton
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import com.skofqq.boxy.util.Format

internal val PROXY_MODES = listOf("rule", "global", "direct")

/**
 * Zashboard's bar above the groups, in Boxy's style: Proxies / Providers with their counts, the core's routing
 * mode, a search over the page and the page's actions (test every group; update / test every provider).
 */
@Composable
internal fun ProxyToolbar(
    providersTab: Boolean,
    groups: Int,
    providers: Int,
    mode: String?,
    query: String,
    busy: Boolean,
    onTab: (Boolean) -> Unit,
    onMode: (String) -> Unit,
    onQuery: (String) -> Unit,
    onUpdateAll: () -> Unit,
    onTestAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.card).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Segment(stringResource(R.string.proxies_tab_groups), groups, !providersTab) { onTab(false) }
            Segment(stringResource(R.string.proxies_tab_providers), providers, providersTab) { onTab(true) }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (mode != null) {
                ModeChip(mode, onMode)
                Spacer(Modifier.width(8.dp))
            }
            BoxyTextField(
                query,
                onQuery,
                stringResource(if (providersTab) R.string.proxies_search_providers else R.string.proxies_search_groups),
                Modifier.weight(1f),
                leading = { Icon(BoxyIcons.Search, null, Modifier.size(18.dp), tint = Boxy.colors.text2) },
                trailing = if (query.isEmpty()) null else ({
                    Icon(BoxyIcons.Close, null, Modifier.size(18.dp).clickable { onQuery("") }, tint = Boxy.colors.text2)
                }),
                container = Boxy.colors.card,
            )
            if (providersTab) {
                Spacer(Modifier.width(8.dp))
                SmallIconButton(BoxyIcons.Refresh, stringResource(R.string.proxies_update_all), enabled = !busy, container = Boxy.colors.card, onClick = onUpdateAll)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    SmallIconButton(BoxyIcons.Bolt, stringResource(R.string.proxies_test_all), container = Boxy.colors.card, onClick = onTestAll)
                }
            }
        }
    }
}

@Composable
private fun Segment(text: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Boxy.colors.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Boxy.colors.accent else Boxy.colors.text2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.width(6.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = if (selected) Boxy.colors.accent.copy(alpha = 0.7f) else Boxy.colors.text2.copy(alpha = 0.7f))
    }
}

@Composable
private fun modeLabel(mode: String): String = stringResource(
    when (mode) {
        "global" -> R.string.proxies_mode_global
        "direct" -> R.string.proxies_mode_direct
        else -> R.string.proxies_mode_rule
    },
)

@Composable
private fun ModeChip(mode: String, onMode: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlassChip(modeLabel(mode), mode != "rule", Boxy.colors.accent, BoxyIcons.ExpandMore) { open = true }
        GlassMenu(open, { open = false }) {
            PROXY_MODES.forEach { m -> GlassMenuItem(modeLabel(m), { open = false; onMode(m) }, selected = m == mode) }
        }
    }
}

@Composable
private fun delayColor(delay: Int?): Color = when {
    delay == null -> Color.Unspecified
    delay <= 0 -> Tints.red.fg
    delay < 300 -> Tints.green.fg
    delay < 800 -> Tints.amber.fg
    else -> Tints.red.fg
}

/**
 * A provider as in Zashboard: name, source and live / total nodes, update and test buttons, the quota bar
 * with used / total and expiry, when it was pulled; its nodes as tiles (tap the head to show or hide them),
 * or as dots coloured by delay while hidden.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderCard(
    provider: ProxyProvider,
    snap: ProxySnapshot,
    delays: Map<String, Int>,
    testing: Map<String, Boolean>,
    updating: Boolean,
    checking: Boolean,
    expanded: Boolean,
    filter: String,
    modifier: Modifier,
    onToggle: () -> Unit,
    onUpdate: () -> Unit,
    onCheck: () -> Unit,
    onTestNode: (String) -> Unit,
) {
    val context = LocalContext.current
    val nodeDelays = provider.nodes.map { n -> delays[n.name] ?: n.delay }
    // The tiles read the provider's own nodes, so they match the count and dots (same names elsewhere differ).
    val local = remember(snap, provider) { snap.copy(nodes = snap.nodes + provider.nodes.associateBy { it.name }) }
    // A search narrows the nodes to the matching ones, shown as tiles (unless it matched the provider itself);
    // the count then says how many of those are alive, out of all nodes (as in Zashboard).
    val shown = if (filter.isBlank() || provider.name.contains(filter, true)) provider.nodes else provider.nodes.filter { it.name.contains(filter, true) }
    val filtered = shown.size != provider.nodes.size
    val alive = shown.count { n -> ((delays[n.name] ?: n.delay) ?: 0) > 0 }
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Boxy.colors.card).padding(14.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    provider.name,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    color = Boxy.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${provider.vehicle.uppercase()} · $alive/${provider.nodes.size}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 1.sp),
                    color = Boxy.colors.text2,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            ProgressButton(BoxyIcons.Bolt, stringResource(R.string.proxies_test), checking, onCheck)
            Spacer(Modifier.width(6.dp))
            ProgressButton(BoxyIcons.Refresh, stringResource(R.string.provider_update), updating, onUpdate)
        }
        provider.sub?.takeIf { it.used > 0 || it.total > 0 }?.let { s ->
            Spacer(Modifier.height(10.dp))
            if (s.total > 0) {
                val f = (s.used.toFloat() / s.total).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Boxy.colors.surface2)) {
                    Box(
                        Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(50)).background(
                            when {
                                f < 0.7f -> Tints.green.fg
                                f < 0.9f -> Tints.amber.fg
                                else -> Tints.red.fg
                            },
                        ),
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${Format.bytes(context, s.used)} / ${if (s.total > 0) Format.bytes(context, s.total) else "∞"}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = Boxy.colors.text2,
                )
                Text(
                    stringResource(
                        R.string.provider_expires,
                        if (s.expire > 0) {
                            DateUtils.formatDateTime(context, s.expire * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
                        } else {
                            stringResource(R.string.provider_no_expiry)
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Boxy.colors.text2,
                )
            }
        }
        provider.updatedAt?.let { at ->
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.provider_updated, timeAgo(at, androidx.compose.ui.platform.LocalConfiguration.current.locales[0])),
                style = MaterialTheme.typography.bodySmall,
                color = Boxy.colors.text2,
            )
        }
        if (shown.isEmpty()) return@Column
        Spacer(Modifier.height(12.dp))
        if (expanded || filtered) {
            shown.map { it.name }.chunked(2).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { name ->
                        NodeTile(
                            name, local, delays,
                            selected = false,
                            busy = testing[name] == true || checking,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = null,
                            onTest = { onTestNode(name) },
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else if (nodeDelays.size <= DOTS_MAX) {
            FlowRow(Modifier.clickable(onClick = onToggle), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                nodeDelays.forEach { d -> Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(delayColor(d).takeOrElse())) }
            }
        } else {
            // Too many for dots: one bar, a slice per node.
            Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50)).clickable(onClick = onToggle)) {
                nodeDelays.forEach { d -> Box(Modifier.weight(1f).fillMaxHeight().background(delayColor(d).takeOrElse())) }
            }
        }
    }
}

private const val DOTS_MAX = 40

/** "6 hours ago" in the app's language (DateUtils would use the system one). */
private fun timeAgo(at: Long, locale: java.util.Locale): String {
    val f = android.icu.text.RelativeDateTimeFormatter.getInstance(locale)
    val min = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
    val (n, unit) = when {
        min < 1 -> return f.format(android.icu.text.RelativeDateTimeFormatter.Direction.PLAIN, android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        min < 60 -> min to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES
        min < 24 * 60 -> min / 60 to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS
        else -> min / (24 * 60) to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS
    }
    return f.format(n.toDouble(), android.icu.text.RelativeDateTimeFormatter.Direction.LAST, unit)
}

@Composable
private fun Color.takeOrElse(): Color = if (this == Color.Unspecified) Boxy.colors.text2.copy(alpha = 0.55f) else this

@Composable
private fun ProgressButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, busy: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else SmallIconButton(icon, description, onClick = onClick)
    }
}
