package com.skofqq.boxy.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.data.HomeSection
import com.skofqq.boxy.data.MetricCard
import com.skofqq.boxy.data.Prefs
import com.skofqq.boxy.net.GeoIp
import com.skofqq.boxy.net.flagEmoji
import com.skofqq.boxy.root.IpsetStatus
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.IconInfoRow
import com.skofqq.boxy.ui.components.InfoRow
import com.skofqq.boxy.ui.components.TitledSheetGroup
import com.skofqq.boxy.ui.components.OptionRow
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.components.SheetGroup
import com.skofqq.boxy.ui.components.SmallIconButton
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import com.skofqq.boxy.util.Format

@Composable
fun HomeSheets(sheet: HomeSheet, vm: HomeViewModel, prefs: Prefs, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dash = stringResource(R.string.common_dash)
    when (sheet) {
        HomeSheet.NONE -> Unit

        HomeSheet.DETAILS -> BoxySheet(stringResource(R.string.details_title), vm.state?.core, onDismiss) {
            val d = vm.details
            SheetGroup {
                InfoRow(stringResource(R.string.details_pid), d?.pid ?: vm.state?.pid ?: dash)
                InfoRow(stringResource(R.string.details_core_version), d?.coreVersion ?: dash)
                InfoRow(stringResource(R.string.details_memory), d?.memoryBytes?.let { Format.bytes(context, it) } ?: dash)
                InfoRow(stringResource(R.string.details_current_cpu), d?.currentCpu ?: dash)
                InfoRow(stringResource(R.string.details_cpu_affinity), d?.cpuAffinity ?: dash)
            }
            SheetButtons(stringResource(R.string.home_reload_config), { vm.reloadConfig(); onDismiss() })
        }

        HomeSheet.GEO -> BoxySheet(stringResource(R.string.geo_title), stringResource(R.string.geo_subtitle), onDismiss) {
            GeoGroup(stringResource(R.string.geo_ipv4), vm.geo4, vm.geoLoading)
            Spacer(Modifier.height(12.dp))
            GeoGroup(stringResource(R.string.geo_ipv6), vm.geo6, vm.geoLoading)
            SheetButtons(stringResource(R.string.action_refresh), { vm.loadGeoDetails(); vm.refreshIp() })
        }

        HomeSheet.SPEED -> BoxySheet(stringResource(R.string.speed_title), stringResource(R.string.speed_details), onDismiss) {
            val s = vm.speed
            SheetGroup {
                Sparkline(s.history, Tints.teal.fg, Modifier.fillMaxWidth().height(90.dp).padding(16.dp))
                InfoRow(stringResource(R.string.speed_down), Format.speed(context, s.down), Tints.blue.fg)
                InfoRow(stringResource(R.string.speed_up), Format.speed(context, s.up), Tints.green.fg)
                InfoRow(stringResource(R.string.speed_fastest_down), Format.speed(context, s.fastestDown))
                InfoRow(stringResource(R.string.speed_fastest_up), Format.speed(context, s.fastestUp))
            }
        }

        HomeSheet.SUBSCRIPTION -> BoxySheet(stringResource(R.string.card_subscription), null, onDismiss) {
            if (vm.subscriptions.isEmpty()) {
                SheetGroup {
                    InfoRow(stringResource(R.string.sub_sheet_none), "")
                    Text(
                        stringResource(R.string.sub_sheet_go_tools),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Boxy.colors.text2,
                    )
                }
            }
            vm.subscriptions.forEachIndexed { i, sub ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                SheetGroup {
                    Text(
                        sub.name ?: "#${i + 1}",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = Boxy.colors.text,
                    )
                    InfoRow(stringResource(R.string.sub_upload), Format.bytes(context, sub.upload))
                    InfoRow(stringResource(R.string.sub_download), Format.bytes(context, sub.download))
                    InfoRow(stringResource(R.string.sub_remaining), if (sub.total > 0) Format.bytes(context, sub.remaining) else dash)
                    InfoRow(stringResource(R.string.sub_used), Format.bytes(context, sub.used))
                    InfoRow(stringResource(R.string.sub_total), if (sub.total > 0) Format.bytes(context, sub.total) else dash)
                    InfoRow(stringResource(R.string.sub_expire_label), Format.date(sub.expire))
                    InfoRow(stringResource(R.string.sub_updated_label), Format.dateTime(sub.updatedAt))
                }
            }
            SheetButtons(stringResource(R.string.action_refresh), vm::refreshSubscription)
        }

        HomeSheet.SYSTEM -> BoxySheet("", null, onDismiss) {
            // Same layout as BFR: service details, then the system environment.
            val d = vm.details
            TitledSheetGroup(stringResource(R.string.details_title)) {
                IconInfoRow(BoxyIcons.Laptop, stringResource(R.string.details_pid), d?.pid ?: vm.state?.pid ?: dash)
                IconInfoRow(
                    BoxyIcons.Memory,
                    stringResource(R.string.details_memory),
                    d?.memoryBytes?.let { rss ->
                        listOfNotNull(
                            "RSS " + Format.bytes(context, rss),
                            d.pssBytes?.let { "PSS " + Format.bytes(context, it) },
                            d.ussBytes?.let { "USS " + Format.bytes(context, it) },
                        ).joinToString(" / ")
                    } ?: dash,
                )
                IconInfoRow(BoxyIcons.Info, stringResource(R.string.details_core_version), d?.coreVersion ?: dash)
                IconInfoRow(BoxyIcons.Tune, stringResource(R.string.details_cpu_affinity), d?.cpuAffinity?.let(::expandCpuList) ?: dash)
                IconInfoRow(BoxyIcons.CenterFocus, stringResource(R.string.details_current_cpu), d?.currentCpu?.let { "Core $it" } ?: dash)
            }
            Spacer(Modifier.height(14.dp))
            val e = vm.systemEnv
            TitledSheetGroup(stringResource(R.string.sysenv_title)) {
                IconInfoRow(BoxyIcons.Android, stringResource(R.string.sysenv_android), e?.android ?: dash)
                IconInfoRow(BoxyIcons.Laptop, stringResource(R.string.sysenv_kernel), e?.kernel ?: dash)
                IconInfoRow(
                    BoxyIcons.Code,
                    stringResource(R.string.sysenv_ipset),
                    when (e?.ipset) {
                        IpsetStatus.AVAILABLE -> stringResource(R.string.ipset_available)
                        IpsetStatus.MISSING_BINARY -> stringResource(R.string.ipset_missing_binary)
                        IpsetStatus.NOT_SUPPORTED -> stringResource(R.string.ipset_not_supported)
                        null -> dash
                    },
                    when (e?.ipset) {
                        IpsetStatus.AVAILABLE -> Tints.green.fg
                        null -> Boxy.colors.text
                        else -> Tints.amber.fg
                    },
                )
                IconInfoRow(BoxyIcons.Memory, stringResource(R.string.sysenv_memory), e?.totalMemoryBytes?.let { Format.bytes(context, it) } ?: dash)
            }
        }

        HomeSheet.LAYOUT -> LayoutSheet(prefs, onDismiss)
    }
}

/** "0-3,6" -> "0,1,2,3,6", as BFR shows the CPU affinity. */
private fun expandCpuList(list: String): String = list.split(',').flatMap { part ->
    val r = part.trim().split('-')
    val a = r[0].toIntOrNull()
    val b = r.getOrNull(1)?.toIntOrNull()
    when {
        a == null -> listOf(part.trim())
        b == null -> listOf(a.toString())
        else -> (a..b).map { it.toString() }
    }
}.joinToString(",")

@Composable
private fun GeoGroup(title: String, geo: GeoIp?, loading: Boolean) {
    val dash = stringResource(R.string.common_dash)
    SheetGroup {
        Text(title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
        if (geo == null) {
            Text(
                stringResource(if (loading) R.string.geo_loading else R.string.geo_unavailable),
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Boxy.colors.text2,
            )
        } else {
            InfoRow(stringResource(R.string.geo_ip), geo.ip ?: dash)
            InfoRow(
                stringResource(R.string.geo_location),
                (geo.location?.ifBlank { null } ?: dash) + flagEmoji(geo.countryCode).let { if (it.isEmpty()) "" else " $it" },
            )
            InfoRow(stringResource(R.string.geo_isp), geo.isp ?: dash)
            InfoRow(stringResource(R.string.geo_asn), geo.asn ?: dash)
        }
    }
}

@Composable
private fun LayoutSheet(prefs: Prefs, onDismiss: () -> Unit) {
    BoxySheet(stringResource(R.string.layout_title), null, onDismiss) {
        Text(stringResource(R.string.layout_sections), style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text2, modifier = Modifier.padding(bottom = 8.dp))
        SheetGroup {
            val sections = HomeSection.entries
            sections.forEachIndexed { i, section ->
                val (title, icon) = when (section) {
                    HomeSection.HERO -> R.string.layout_section_hero to BoxyIcons.Home
                    HomeSection.QUICK -> R.string.layout_section_quick to BoxyIcons.Apps
                    HomeSection.LATENCY -> R.string.layout_section_latency to BoxyIcons.Router
                    HomeSection.GRID -> R.string.layout_section_grid to BoxyIcons.Tune
                }
                SwitchRow(
                    icon,
                    stringResource(title),
                    checked = section !in prefs.hiddenSections,
                    showDivider = i < sections.lastIndex,
                ) { prefs.setSectionVisible(section, it) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.layout_metrics), style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text2, modifier = Modifier.padding(bottom = 8.dp))
        SheetGroup {
            prefs.metricOrder.forEachIndexed { i, card ->
                val title = when (card) {
                    MetricCard.IP -> R.string.card_ip
                    MetricCard.SPEED -> R.string.card_speed
                    MetricCard.SUBSCRIPTION -> R.string.card_subscription
                    MetricCard.SYSTEM -> R.string.card_system
                }
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
                    }
                    SmallIconButton(BoxyIcons.ExpandLess, stringResource(R.string.layout_move_up), enabled = i > 0) { prefs.moveMetric(card, -1) }
                    Spacer(Modifier.width(6.dp))
                    SmallIconButton(BoxyIcons.ExpandMore, stringResource(R.string.layout_move_down), enabled = i < prefs.metricOrder.lastIndex) { prefs.moveMetric(card, 1) }
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.Switch(
                        checked = card !in prefs.hiddenMetrics,
                        onCheckedChange = { prefs.setMetricVisible(card, it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Boxy.colors.accent),
                    )
                }
            }
        }
        SheetButtons(stringResource(R.string.action_done), onDismiss, stringResource(R.string.action_reset), prefs::resetHomeLayout)
    }
}

