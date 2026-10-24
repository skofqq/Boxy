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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
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
 * mode held to the right edge. The search and the page's actions float at the bottom ([FloatingSearch]).
 */
@Composable
internal fun ProxyToolbar(
    providersTab: Boolean,
    groups: Int,
    providers: Int,
    mode: String?,
    onTab: (Boolean) -> Unit,
    onMode: (String) -> Unit,
) {
    PageBar(
        listOf(stringResource(R.string.proxies_tab_groups) to groups, stringResource(R.string.proxies_tab_providers) to providers),
        if (providersTab) 1 else 0,
        { onTab(it == 1) },
    ) {
        if (mode != null) MenuChip(modeLabel(mode), mode != "rule", PROXY_MODES.map { it to modeLabel(it) }, mode, onMode)
    }
}

/**
 * The bar on top of a panel tab: a switch between the tab's lists (with their counts) on the left and
 * [end] (a [MenuChip]) held to the right edge.
 */
@Composable
internal fun PageBar(segments: List<Pair<String, Int?>>, selected: Int, onSelect: (Int) -> Unit, end: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.card).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            segments.forEachIndexed { i, (text, count) -> Segment(text, count, i == selected) { onSelect(i) } }
        }
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        end()
    }
}

/**
 * The page's search as a glass pill floating over the bottom of the list, with the page's [actions] beside it
 * as [GlassAction]s. It rises with the keyboard.
 */
@Composable
internal fun FloatingSearch(
    query: String,
    onQuery: (String) -> Unit,
    hint: String,
    /** Room under the bar (the app's bottom padding); the fade runs through it to the screen edge. */
    bottom: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    // The list fades into the page under the bar, so the pills stand apart from the cards behind them.
    val page = Boxy.colors.page
    Box(
        modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to page.copy(alpha = 0f), 0.45f to page.copy(alpha = 0.85f), 1f to page))
            .padding(top = 24.dp, bottom = bottom),
    ) {
    Row(
        Modifier
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.skofqq.boxy.ui.components.GlassSurface(RoundedCornerShape(50), Boxy.colors.card, 26.dp, Modifier.weight(1f).fillMaxHeight().floating(), element = true) {
            BoxyTextField(
                query,
                onQuery,
                hint,
                Modifier.fillMaxSize(),
                leading = { Icon(BoxyIcons.Search, null, Modifier.size(20.dp), tint = Boxy.colors.text2) },
                trailing = if (query.isEmpty()) null else ({
                    Icon(BoxyIcons.Close, null, Modifier.size(20.dp).clickable { onQuery("") }, tint = Boxy.colors.text2)
                }),
                container = Color.Transparent,
            )
        }
        actions()
    }
    }
}

/** Lifts a pill off the list like the bottom navigation bar: a soft shadow and a thin outline. */
@Composable
private fun Modifier.floating(): Modifier {
    val pill = RoundedCornerShape(50)
    return shadow(10.dp, pill, clip = false).border(1.dp, Boxy.colors.outline, pill)
}

/** A round glass button the height of the floating search; shows a spinner while its work runs. */
@Composable
internal fun GlassAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    com.skofqq.boxy.ui.components.GlassSurface(
        RoundedCornerShape(50),
        Boxy.colors.card,
        26.dp,
        Modifier.size(52.dp).floating().clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick),
        element = true,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, description, Modifier.size(22.dp), tint = Boxy.colors.text.copy(alpha = if (enabled) 1f else 0.3f))
            }
        }
    }
}

/** One small size for the whole top row, so the switch, the mode and the buttons fit on a phone. */
@Composable
private fun barText() = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp)

@Composable
private fun Segment(text: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Boxy.colors.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = barText(),
            color = if (selected) Boxy.colors.accent else Boxy.colors.text2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), style = barText(), color = if (selected) Boxy.colors.accent.copy(alpha = 0.7f) else Boxy.colors.text2.copy(alpha = 0.7f))
        }
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

/** A pill showing [label] that opens a menu of [options] (key to label); tinted when [highlighted]. */
@Composable
internal fun MenuChip(label: String, highlighted: Boolean, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50))
                .background(if (highlighted) Boxy.colors.accent.copy(alpha = 0.16f) else Boxy.colors.card)
                .clickable { open = true }
                .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.widthIn(max = 110.dp), style = barText(), color = if (highlighted) Boxy.colors.accent else Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(2.dp))
            Icon(BoxyIcons.ExpandMore, null, Modifier.size(18.dp), tint = Boxy.colors.text2)
        }
        GlassMenu(open, { open = false }) {
            options.forEach { (key, text) -> GlassMenuItem(text, { open = false; onPick(key) }, selected = key == selected) }
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
internal fun timeAgo(at: Long, locale: java.util.Locale): String {
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
internal fun ProgressButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, busy: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else SmallIconButton(icon, description, onClick = onClick)
    }
}
