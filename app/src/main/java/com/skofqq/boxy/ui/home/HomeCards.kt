package com.skofqq.boxy.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skofqq.boxy.R
import com.skofqq.boxy.net.GeoIp
import com.skofqq.boxy.net.LanAddress
import com.skofqq.boxy.net.SubscriptionInfo
import com.skofqq.boxy.net.flagEmoji
import com.skofqq.boxy.root.ServiceState
import com.skofqq.boxy.ui.components.Badge
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tint
import com.skofqq.boxy.ui.theme.Tints
import com.skofqq.boxy.util.Format

private val CardShape = RoundedCornerShape(26.dp)

@Composable
fun HomeCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(CardShape)
            .background(Boxy.colors.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun CardTitle(title: String, badge: String?, tint: Tint, onBadgeClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
            color = Boxy.colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            Badge(badge, tint, if (onBadgeClick != null) Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onBadgeClick) else Modifier)
        }
    }
}

// ---------- Hero ----------

@Composable
fun HeroCard(
    state: ServiceState?,
    busy: Busy,
    onStatusClick: () -> Unit,
    onCore: () -> Unit,
    onMode: () -> Unit,
    onIpv6: () -> Unit,
    dnscrypt: Pair<Boolean?, Boolean>? = null,
    onDnscrypt: () -> Unit = {},
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val colors = Boxy.colors
    val context = LocalContext.current
    val running = state?.running == true
    val green = Tints.green
    val (label, labelColor) = when {
        busy == Busy.STARTING -> stringResource(R.string.status_starting) to colors.text
        busy == Busy.STOPPING -> stringResource(R.string.status_stopping) to colors.text
        busy == Busy.RESTARTING -> stringResource(R.string.status_restarting) to colors.text
        state == null -> stringResource(R.string.status_checking) to colors.text
        running -> stringResource(R.string.status_running) to green.fg
        else -> stringResource(R.string.status_stopped) to colors.text
    }
    HomeCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier.weight(1f).clickable(enabled = running, onClick = onStatusClick).padding(end = 8.dp),
            ) {
                Text(stringResource(R.string.home_service_status), style = MaterialTheme.typography.titleMedium, color = colors.text2)
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.headlineMedium, color = labelColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                val hint = when {
                    running && state?.uptimeSec != null -> Format.uptime(context, state.uptimeSec)
                    running -> stringResource(R.string.home_pid, state?.pid ?: "")
                    else -> stringResource(R.string.home_tap_start)
                }
                Text(
                    hint,
                    Modifier.clip(RoundedCornerShape(20.dp)).background(colors.surface2).padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.text,
                )
            }
            Column(
                Modifier.width(IntrinsicMiniWidth).clip(RoundedCornerShape(22.dp)).background(colors.surface2).padding(vertical = 8.dp),
            ) {
                MiniRow(stringResource(R.string.home_core), state?.core, onCore)
                MiniRow(stringResource(R.string.home_mode), state?.mode, onMode)
                MiniRow(
                    stringResource(R.string.home_ipv6),
                    state?.ipv6?.let { stringResource(if (it) R.string.common_on else R.string.common_off) },
                    onIpv6,
                )
                val (dcEnabled, dcRunning) = dnscrypt ?: (null to false)
                if (dcEnabled != null) {
                    // On and working: green. On, service running, dnscrypt-proxy down: red. Otherwise plain.
                    val failed = dcEnabled && running && !dcRunning
                    MiniRow(
                        "DNSCrypt",
                        stringResource(
                            when {
                                !dcEnabled -> R.string.common_off
                                failed -> R.string.dnscrypt_home_failed
                                else -> R.string.common_on
                            },
                        ),
                        onDnscrypt,
                        color = when {
                            failed -> Tints.red.fg
                            dcEnabled && dcRunning -> green.fg
                            else -> colors.text
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(colors.surface2.copy(alpha = 0.6f)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val idle = busy == Busy.NONE && state != null
            ActionPill(stringResource(R.string.action_start), Tints.green, idle && !running, Modifier.weight(1f), onStart)
            ActionPill(stringResource(R.string.action_stop), Tints.red, idle && running, Modifier.weight(1f), onStop)
            ActionPill(stringResource(R.string.action_restart), Tints.amber, idle && running, Modifier.weight(1f), onRestart)
        }
    }
}

private val IntrinsicMiniWidth = 150.dp

@Composable
private fun MiniRow(label: String, value: String?, onClick: () -> Unit, color: androidx.compose.ui.graphics.Color = Boxy.colors.text) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 1)
        Text(
            value ?: stringResource(R.string.common_dash),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionPill(text: String, tint: Tint, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(tint.bg.copy(alpha = if (enabled) tint.bg.alpha else tint.bg.alpha * 0.5f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = tint.fg.copy(alpha = if (enabled) 1f else 0.45f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------- Quick actions ----------

@Composable
fun QuickCard(title: String, subtitle: String, tint: Tint, modifier: Modifier, onClick: () -> Unit) {
    val glow = tint.fg.copy(alpha = if (Boxy.colors.isDark) 0.16f else 0.10f)
    Box(
        modifier
            .clip(CardShape)
            .background(Boxy.colors.card)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(size.width, size.height), radius = size.width * 0.55f),
                    radius = size.width * 0.55f,
                    center = Offset(size.width, size.height),
                )
            }
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(tint.fg))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), color = Boxy.colors.text, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Icon(BoxyIcons.ChevronRight, null, Modifier.align(Alignment.End).size(22.dp), tint = Boxy.colors.text2)
        }
    }
}

// ---------- Latency ----------

@Composable
fun LatencyCard(results: List<LatencyResult>, badge: LatencyBadge, onClick: () -> Unit) {
    val context = LocalContext.current
    val (badgeText, badgeTint) = when (badge) {
        LatencyBadge.OK -> stringResource(R.string.badge_ok) to Tints.green
        LatencyBadge.PART -> stringResource(R.string.badge_part) to Tints.amber
        LatencyBadge.DOWN -> stringResource(R.string.badge_down) to Tints.red
        LatencyBadge.TEST -> stringResource(R.string.badge_test) to Tints.blue
    }
    HomeCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = onClick) {
        CardTitle(stringResource(R.string.latency_title), badgeText, badgeTint)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            results.forEach { r ->
                val color = when {
                    r.ms == null -> if (badge == LatencyBadge.TEST) Boxy.colors.text2 else Tints.red.fg
                    r.ms < 300 -> Tints.green.fg
                    r.ms < 800 -> Tints.amber.fg
                    else -> Tints.red.fg
                }
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Boxy.colors.surface2)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(r.name, style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            r.ms != null -> Format.ms(context, r.ms)
                            badge == LatencyBadge.TEST -> "…"
                            else -> stringResource(R.string.latency_timeout)
                        },
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = color,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------- Metrics ----------

@Composable
fun IpCard(lan: LanAddress?, wan: GeoIp?, wanMode: Boolean, modifier: Modifier, onToggle: () -> Unit, onClick: () -> Unit) {
    val showWan = wanMode && wan != null
    val badge = if (showWan) {
        stringResource(R.string.badge_wan) + flagEmoji(wan.countryCode).let { if (it.isEmpty()) "" else " $it" }
    } else {
        stringResource(R.string.badge_lan)
    }
    HomeCard(modifier, onToggle) {
        CardTitle(stringResource(R.string.card_ip), if (wanMode && wan == null) stringResource(R.string.badge_wan) else badge, Tints.green, if (wanMode) onClick else onToggle)
        Spacer(Modifier.height(16.dp))
        Text(
            (if (showWan) wan.ip else if (wanMode) null else lan?.ip) ?: stringResource(R.string.common_dash),
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp),
            color = Boxy.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (showWan) {
                    stringResource(R.string.ip_region, wan.country ?: wan.countryCode ?: "-")
                } else {
                    stringResource(R.string.ip_interface, lan?.iface ?: "-")
                },
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = Boxy.colors.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (wanMode) {
                Icon(
                    BoxyIcons.ChevronRight,
                    null,
                    Modifier.size(28.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(2.dp),
                    tint = Boxy.colors.text2,
                )
            }
        }
    }
}

@Composable
fun SpeedCard(speed: SpeedState, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val teal = Tints.teal
    HomeCard(modifier, onClick) {
        CardTitle(stringResource(R.string.card_speed), stringResource(R.string.badge_net), Tints.orange)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SpeedLine(BoxyIcons.ArrowUp, Tints.green.fg, Format.speed(context, speed.up))
                SpeedLine(BoxyIcons.ArrowDown, Tints.blue.fg, Format.speed(context, speed.down))
            }
            Sparkline(speed.history, teal.fg, Modifier.width(64.dp).height(34.dp))
        }
    }
}

@Composable
private fun SpeedLine(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = Boxy.colors.text, maxLines = 1)
    }
}

@Composable
fun Sparkline(values: List<Long>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) {
            drawLine(color.copy(alpha = 0.6f), Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())
            return@Canvas
        }
        val max = values.max().coerceAtLeast(1).toFloat()
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - (v / max) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun SubscriptionCard(subs: List<SubscriptionInfo>, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val first = subs.firstOrNull()
    HomeCard(modifier, onClick) {
        CardTitle(stringResource(R.string.card_subscription), stringResource(R.string.badge_sub), Tints.purple)
        Spacer(Modifier.height(12.dp))
        Row {
            Text(stringResource(R.string.sub_used), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 1)
            Text(stringResource(R.string.sub_total), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                first?.let { Format.bytes(context, it.used) } ?: "-",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = Boxy.colors.text,
                maxLines = 1,
            )
            Text(
                first?.takeIf { it.total > 0 }?.let { Format.bytes(context, it.total) } ?: "-",
                style = MaterialTheme.typography.labelLarge,
                color = Boxy.colors.text,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(10.dp))
        val fraction = first?.takeIf { it.total > 0 }?.let { (it.used.toFloat() / it.total).coerceIn(0f, 1f) } ?: 0f
        val purple = Tints.purple.fg
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Boxy.colors.surface2)) {
            Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(purple))
        }
    }
}

@Composable
fun SystemCard(system: SystemState?, running: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    HomeCard(modifier, onClick) {
        CardTitle(stringResource(R.string.card_system), stringResource(R.string.badge_sys), Tints.gray)
        Spacer(Modifier.height(12.dp))
        if (!running || system == null) {
            Text(
                stringResource(R.string.system_stopped_hint),
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tints.red.bg.copy(alpha = 0.10f)).padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Boxy.colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MeterLine(stringResource(R.string.system_cpu), String.format(java.util.Locale.getDefault(), "%.1f%%", system.cpuPercent), (system.cpuPercent / 100f).coerceIn(0f, 1f), Tints.blue.fg)
                MeterLine(stringResource(R.string.system_ram), Format.bytes(context, system.rssBytes), null, Tints.teal.fg)
            }
        }
    }
}

@Composable
private fun MeterLine(label: String, value: String, fraction: Float?, color: Color) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
            Text(value, style = MaterialTheme.typography.labelLarge, color = Boxy.colors.text, maxLines = 1)
        }
        if (fraction != null) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(Boxy.colors.surface2)) {
                Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
            }
        }
    }
}
