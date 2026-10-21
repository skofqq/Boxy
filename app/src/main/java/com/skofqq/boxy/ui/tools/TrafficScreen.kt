package com.skofqq.boxy.ui.tools

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.data.TrafficBucket
import com.skofqq.boxy.data.TrafficStats
import com.skofqq.boxy.net.Net
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.ui.components.ConfirmDialog
import com.skofqq.boxy.ui.components.HeaderAction
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import com.skofqq.boxy.util.Format
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Proxy traffic per day and per month, recorded by [TrafficStats]. */
@Composable
fun TrafficScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf<Map<LocalDate, TrafficBucket>?>(null) }
    var apiAvailable by remember { mutableStateOf(true) }
    var period by rememberSaveable { mutableStateOf(0) } // 0 days, 1 weeks, 2 months
    var resetDialog by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        // Take a fresh sample so today's numbers are current.
        TrafficStats.sample(context)
        val core = BoxModule.readSetting("bin_name")
        apiAvailable = Net.clashApi(core) != null
        days = TrafficStats.days(context)
    }

    val down = Tints.blue.fg
    val up = Tints.green.fg
    PinnedLazyPage(contentPadding, header = {
        SubPageHeader(stringResource(R.string.traffic_title), stringResource(R.string.traffic_subtitle), onBack) {
            HeaderAction(BoxyIcons.Refresh, stringResource(R.string.action_refresh)) { reload++ }
            HeaderAction(BoxyIcons.Delete, stringResource(R.string.traffic_reset)) { resetDialog = true }
        }
    }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val d = days ?: return@PinnedLazyPage
        val today = LocalDate.now()
        val months = TrafficStats.months(d)
        val weeks = d.entries.groupBy({ weekStart(it.key) }, { it.value }).mapValues { (_, v) -> v.fold(TrafficBucket.ZERO) { a, b -> a + b } }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TotalCard(stringResource(R.string.traffic_today), d[today] ?: TrafficBucket.ZERO, down, up, Modifier.weight(1f))
                TotalCard(stringResource(R.string.traffic_this_month), months[YearMonth.from(today)] ?: TrafficBucket.ZERO, down, up, Modifier.weight(1f))
            }
        }
        if (!apiAvailable) {
            item {
                Text(
                    stringResource(R.string.traffic_no_api),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp)).background(Tints.amber.bg).padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Boxy.colors.text,
                )
            }
        }
        item {
            SectionCard(null) {
                Row(Modifier.padding(horizontal = 18.dp).clip(RoundedCornerShape(50)).background(Boxy.colors.surface2).padding(4.dp)) {
                    Segment(stringResource(R.string.traffic_by_day), period == 0) { period = 0 }
                    Segment(stringResource(R.string.traffic_by_week), period == 1) { period = 1 }
                    Segment(stringResource(R.string.traffic_by_month), period == 2) { period = 2 }
                }
                val locale = appLocale
                val bars: List<Pair<String, TrafficBucket>> = when (period) {
                    2 -> (11 downTo 0).map { i ->
                        val m = YearMonth.from(today).minusMonths(i.toLong())
                        m.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale).take(3) to (months[m] ?: TrafficBucket.ZERO)
                    }
                    1 -> (7 downTo 0).map { i ->
                        val start = weekStart(today).minusWeeks(i.toLong())
                        start.format(DateTimeFormatter.ofPattern("d.MM", locale)) to (weeks[start] ?: TrafficBucket.ZERO)
                    }
                    else -> (13 downTo 0).map { i ->
                        val day = today.minusDays(i.toLong())
                        day.dayOfMonth.toString() to (d[day] ?: TrafficBucket.ZERO)
                    }
                }
                Bars(bars, down, up, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp))
                Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Legend(stringResource(R.string.speed_down), down)
                    Spacer(Modifier.width(16.dp))
                    Legend(stringResource(R.string.speed_up), up)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        item { AppTrafficSection(reload) }
        item {
            SectionCard(stringResource(listOf(R.string.traffic_by_day, R.string.traffic_by_week, R.string.traffic_by_month)[period])) {
                val rows: List<Pair<String, TrafficBucket>> = when (period) {
                    2 -> months.entries.sortedByDescending { it.key }.map { (m, b) ->
                        m.format(DateTimeFormatter.ofPattern("LLLL yyyy", appLocale)).replaceFirstChar { it.titlecase() } to b
                    }
                    1 -> weeks.entries.sortedByDescending { it.key }.map { (start, b) -> weekRange(start, appLocale) to b }
                    else -> d.entries.sortedByDescending { it.key }.take(60).map { (day, b) ->
                        day.format(DateTimeFormatter.ofPattern("d MMMM, EEE", appLocale)) to b
                    }
                }
                if (rows.isEmpty()) {
                    Text(stringResource(R.string.traffic_empty), Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = Boxy.colors.text2)
                }
                rows.forEach { (label, b) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Format.bytes(context, b.total), style = MaterialTheme.typography.labelLarge, color = Boxy.colors.text)
                            Text(
                                "↓ ${Format.bytes(context, b.down)}  ↑ ${Format.bytes(context, b.up)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Boxy.colors.text2,
                            )
                        }
                    }
                }
            }
        }
    }

    if (resetDialog) {
        ConfirmDialog(
            stringResource(R.string.traffic_reset_title),
            stringResource(R.string.traffic_reset_body),
            stringResource(R.string.traffic_reset),
            danger = true,
            onConfirm = {
                resetDialog = false
                scope.launch { TrafficStats.reset(context); reload++ }
            },
            onDismiss = { resetDialog = false },
        )
    }
}

@Composable
private fun TotalCard(title: String, b: TrafficBucket, down: Color, up: Color, modifier: Modifier) {
    val context = LocalContext.current
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(Boxy.colors.card).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text2)
        Text(Format.bytes(context, b.total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Boxy.colors.text)
        Spacer(Modifier.height(4.dp))
        Text("↓ ${Format.bytes(context, b.down)}", style = MaterialTheme.typography.bodyMedium, color = down)
        Text("↑ ${Format.bytes(context, b.up)}", style = MaterialTheme.typography.bodyMedium, color = up)
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Boxy.colors.card else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Boxy.colors.text else Boxy.colors.text2,
    )
}

@Composable
private fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
    }
}

/** Stacked bars (download under upload) with labels below. */
@Composable
private fun Bars(bars: List<Pair<String, TrafficBucket>>, down: Color, up: Color, modifier: Modifier) {
    val max = bars.maxOfOrNull { it.second.total }?.takeIf { it > 0 } ?: 1L
    val empty = Boxy.colors.surface2
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val n = bars.size
            val slot = size.width / n
            val w = slot * 0.62f
            val r = CornerRadius(w / 3, w / 3)
            bars.forEachIndexed { i, (_, b) ->
                val x = i * slot + (slot - w) / 2
                val hDown = size.height * b.down / max
                val hUp = size.height * b.up / max
                if (b.total == 0L) {
                    drawRoundRect(empty, Offset(x, size.height - 4.dp.toPx()), Size(w, 4.dp.toPx()), r)
                } else {
                    drawRoundRect(down, Offset(x, size.height - hDown), Size(w, hDown.coerceAtLeast(2f)), r)
                    drawRoundRect(up, Offset(x, size.height - hDown - hUp), Size(w, hUp.coerceAtLeast(if (b.up > 0) 2f else 0f)), r)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { (label, _) ->
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Boxy.colors.text2, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

private fun weekStart(day: LocalDate): LocalDate = day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

/** "22–28 сентября" or "29 сент. – 5 окт." */
private fun weekRange(start: LocalDate, locale: Locale): String {
    val end = start.plusDays(6)
    return if (start.month == end.month) {
        "${start.dayOfMonth}–" + end.format(DateTimeFormatter.ofPattern("d MMMM", locale))
    } else {
        start.format(DateTimeFormatter.ofPattern("d MMM", locale)) + " – " + end.format(DateTimeFormatter.ofPattern("d MMM", locale))
    }
}

/** Language chosen in Boxy (the activity's configuration), not the system one. */
internal val appLocale: Locale
    @Composable get() = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
