package com.skofqq.boxy.ui.proxies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import com.skofqq.boxy.configs.CoreSettings
import com.skofqq.boxy.net.XrayApi
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.ui.components.InputDialog
import com.skofqq.boxy.ui.components.NoticeCard
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import kotlinx.coroutines.launch

/** Choices offered for a URL or a duration; "Other…" opens a field. */
internal val PROBE_URLS = listOf(
    "http://www.gstatic.com/generate_204" to "gstatic.com",
    "https://www.gstatic.com/generate_204" to "gstatic.com (HTTPS)",
    "http://cp.cloudflare.com/generate_204" to "cloudflare.com",
    "http://www.apple.com/library/test/success.html" to "apple.com",
)
private val INTERVALS = listOf("1m", "3m", "5m", "10m", "30m")
private const val CUSTOM = "\u0000custom"

@Composable
private fun RowIcon(icon: ImageVector) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Boxy.colors.surface2), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = Boxy.colors.text)
    }
}

@Composable
private fun RowText(title: String, hint: String?, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
        if (!hint.isNullOrEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Divider(show: Boolean) {
    if (show) HorizontalDivider(Modifier.padding(start = 76.dp, end = 18.dp), color = Boxy.colors.outline)
}

/** A setting with a fixed set of values: a menu on the right, never typed in. */
@Composable
internal fun ChoiceRow(icon: ImageVector, title: String, hint: String?, value: String, options: List<Pair<String, String>>, busy: Boolean, divider: Boolean = true, onPick: (String) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RowIcon(icon)
            Spacer(Modifier.width(14.dp))
            RowText(title, hint, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else MenuChip(options.firstOrNull { it.first == value }?.second ?: value, false, options, value) { if (it != value) onPick(it) }
        }
        Divider(divider)
    }
}

/** An on / off setting. */
@Composable
internal fun ToggleRow(icon: ImageVector, title: String, hint: String?, checked: Boolean, busy: Boolean, divider: Boolean = true, onChange: (Boolean) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !busy) { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(icon)
            Spacer(Modifier.width(14.dp))
            RowText(title, hint, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Switch(checked, { onChange(it) })
        }
        Divider(divider)
    }
}

/** A URL or a number: presets in the menu, "Other…" asks for the value (checked by [validate]). */
@Composable
internal fun PresetRow(
    icon: ImageVector, title: String, hint: String?, value: String, presets: List<Pair<String, String>>, busy: Boolean,
    validate: (String) -> String?, divider: Boolean = true, onPick: (String) -> Unit,
) {
    var custom by remember { mutableStateOf(false) }
    val other = stringResource(R.string.cs_custom)
    val options = (if (presets.none { it.first == value }) listOf(value to value) else emptyList()) + presets + (CUSTOM to other)
    ChoiceRow(icon, title, hint, value, options, busy, divider) { if (it == CUSTOM) custom = true else onPick(it) }
    if (custom) InputDialog(
        title,
        listOf(title to value),
        validate = { v -> validate(v[0].trim()) },
        onConfirm = { v -> custom = false; onPick(v[0].trim()) },
        onDismiss = { custom = false },
    )
}

/** A setting that does something on a tap. */
@Composable
internal fun TapRow(icon: ImageVector, title: String, hint: String?, busy: Boolean, divider: Boolean = true, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RowIcon(icon)
            Spacer(Modifier.width(14.dp))
            RowText(title, hint, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(BoxyIcons.ChevronRight, null, Modifier.size(20.dp), tint = Boxy.colors.text2)
            }
        }
        Divider(divider)
    }
}

private fun durationOk(v: String) = Regex("^\\d+(ms|s|m|h)$").matches(v)
private fun urlOk(v: String) = v.startsWith("http://") || v.startsWith("https://")

/** Xray's Settings tab: everything is changed in the config, checked by Xray, and Xray restarts. */
@Composable
internal fun XraySettingsTab(bottom: Dp, reloadKey: Int, running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var v by remember { mutableStateOf<Map<String, String>?>(null) }
    var access by remember { mutableStateOf<Boolean?>(null) }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(reloadKey, reload) {
        v = CoreSettings.xrayValues(context)
        access = runCatching { XrayApi.setup().accessLog != null }.getOrNull()
    }
    val commentsMsg = stringResource(R.string.cs_comments)
    fun set(key: String, value: String) {
        if (busy[key] == true) return
        busy[key] = true
        scope.launch {
            val r = CoreSettings.setXray(context.applicationContext, key, value)
            busy[key] = false
            val msg = r.exceptionOrNull()?.message?.let { if (it == "comments") commentsMsg else it.lines().takeLast(2).joinToString(" ") }
            com.skofqq.boxy.ui.components.BoxyToast.makeText(context, if (r.isSuccess) context.getString(R.string.cs_applied) else context.getString(R.string.cs_failed, msg), if (r.isSuccess) com.skofqq.boxy.ui.components.BoxyToast.LENGTH_SHORT else com.skofqq.boxy.ui.components.BoxyToast.LENGTH_LONG).show()
            reload++
        }
    }
    fun task(key: String, block: suspend () -> Boolean) {
        if (busy[key] == true) return
        busy[key] = true
        scope.launch {
            val ok = block()
            busy[key] = false
            com.skofqq.boxy.ui.components.BoxyToast.makeText(context, if (ok) R.string.panel_done else R.string.op_failed, com.skofqq.boxy.ui.components.BoxyToast.LENGTH_SHORT).show()
            reload++
        }
    }
    PinnedLazyPage(PaddingValues(bottom = bottom + 16.dp), header = {}, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val m = v
        if (m == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@PinnedLazyPage
        }
        val b = { k: String -> busy[k] == true }
        item { NoticeCard(stringResource(R.string.xray_settings_title), stringResource(R.string.cs_restart_note_xray), Modifier.padding(horizontal = 2.dp)) }
        item {
            SectionCard(stringResource(R.string.cs_section_log)) {
                ChoiceRow(BoxyIcons.Description, stringResource(R.string.xray_settings_loglevel), stringResource(R.string.cs_loglevel_hint), m.getValue(CoreSettings.X_LOGLEVEL),
                    listOf("debug", "info", "warning", "error", "none").map { it to it }, b(CoreSettings.X_LOGLEVEL)) { set(CoreSettings.X_LOGLEVEL, it) }
                ToggleRow(BoxyIcons.Dns, stringResource(R.string.cs_dnslog), stringResource(R.string.cs_dnslog_hint), m[CoreSettings.X_DNSLOG] == "true", b(CoreSettings.X_DNSLOG)) { set(CoreSettings.X_DNSLOG, it.toString()) }
                ToggleRow(BoxyIcons.Link, stringResource(R.string.xray_access_log), stringResource(R.string.cs_access_hint), access == true, b("access"), divider = false) { on ->
                    task("access") { XrayApi.setAccessLog(on).isSuccess }
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.cs_section_probe)) {
                ChoiceRow(BoxyIcons.Schedule, stringResource(R.string.xray_settings_probe_interval), stringResource(R.string.cs_probe_interval_hint), m.getValue(CoreSettings.X_PROBE_INTERVAL),
                    INTERVALS.map { it to it }, b(CoreSettings.X_PROBE_INTERVAL)) { set(CoreSettings.X_PROBE_INTERVAL, it) }
                ChoiceRow(BoxyIcons.Speed, stringResource(R.string.cs_probe_timeout), stringResource(R.string.cs_probe_timeout_hint), m.getValue(CoreSettings.X_PROBE_TIMEOUT),
                    listOf("3s", "5s", "10s").map { it to it }, b(CoreSettings.X_PROBE_TIMEOUT)) { set(CoreSettings.X_PROBE_TIMEOUT, it) }
                ChoiceRow(BoxyIcons.BarChart, stringResource(R.string.cs_probe_sampling), stringResource(R.string.cs_probe_sampling_hint), m.getValue(CoreSettings.X_PROBE_SAMPLING),
                    listOf("1", "2", "3", "5").map { it to it }, b(CoreSettings.X_PROBE_SAMPLING)) { set(CoreSettings.X_PROBE_SAMPLING, it) }
                PresetRow(BoxyIcons.Web, stringResource(R.string.xray_settings_probe_url), stringResource(R.string.cs_probe_url_hint), m.getValue(CoreSettings.X_PROBE_URL),
                    PROBE_URLS, b(CoreSettings.X_PROBE_URL), { if (urlOk(it)) null else "http(s)://…" }, divider = false) { set(CoreSettings.X_PROBE_URL, it) }
            }
        }
        item {
            SectionCard(stringResource(R.string.cs_section_groups)) {
                ChoiceRow(BoxyIcons.Bolt, stringResource(R.string.cs_strategy), stringResource(R.string.cs_strategy_hint), m.getValue(CoreSettings.X_STRATEGY), listOf(
                    "leastLoad" to stringResource(R.string.cs_strategy_load), "leastPing" to stringResource(R.string.cs_strategy_ping),
                    "random" to stringResource(R.string.cs_strategy_random), "roundRobin" to stringResource(R.string.cs_strategy_rr),
                ), b(CoreSettings.X_STRATEGY)) { set(CoreSettings.X_STRATEGY, it) }
                ChoiceRow(BoxyIcons.Speed, stringResource(R.string.cs_maxrtt), stringResource(R.string.cs_maxrtt_hint), m.getValue(CoreSettings.X_MAXRTT),
                    listOf("1s", "2s", "3s", "5s", "10s").map { it to it }, b(CoreSettings.X_MAXRTT), divider = false) { set(CoreSettings.X_MAXRTT, it) }
            }
        }
        item {
            SectionCard(stringResource(R.string.cs_section_routing)) {
                ToggleRow(BoxyIcons.Block, stringResource(R.string.cs_block_quic), stringResource(R.string.cs_block_quic_hint), m[CoreSettings.X_BLOCK_QUIC] == "true", b(CoreSettings.X_BLOCK_QUIC), divider = false) {
                    set(CoreSettings.X_BLOCK_QUIC, it.toString())
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.cs_section_sniff)) {
                ToggleRow(BoxyIcons.Search, stringResource(R.string.cs_sniff), stringResource(R.string.cs_sniff_hint), m[CoreSettings.X_SNIFF] == "true", b(CoreSettings.X_SNIFF)) { set(CoreSettings.X_SNIFF, it.toString()) }
                ToggleRow(BoxyIcons.FilterList, stringResource(R.string.cs_sniff_route), stringResource(R.string.cs_sniff_route_hint), m[CoreSettings.X_SNIFF_ROUTE_ONLY] == "true", b(CoreSettings.X_SNIFF_ROUTE_ONLY)) {
                    set(CoreSettings.X_SNIFF_ROUTE_ONLY, it.toString())
                }
                ChoiceRow(BoxyIcons.Code, stringResource(R.string.cs_sniff_dest), stringResource(R.string.cs_sniff_dest_hint), m.getValue(CoreSettings.X_SNIFF_DEST),
                    listOf("http,tls", "http,tls,quic", "http,tls,quic,fakedns").map { it to it.replace(",", " · ") }, b(CoreSettings.X_SNIFF_DEST), divider = false) { set(CoreSettings.X_SNIFF_DEST, it) }
            }
        }
        item {
            SectionCard("DNS") {
                ChoiceRow(BoxyIcons.IpAddress, stringResource(R.string.cs_dns_query), stringResource(R.string.cs_dns_query_hint), m.getValue(CoreSettings.X_DNS_QUERY), listOf(
                    "UseIP" to stringResource(R.string.cs_ip_both), "UseIPv4" to stringResource(R.string.cs_ip_v4), "UseIPv6" to stringResource(R.string.cs_ip_v6),
                ), b(CoreSettings.X_DNS_QUERY)) { set(CoreSettings.X_DNS_QUERY, it) }
                ToggleRow(BoxyIcons.Delete, stringResource(R.string.cs_dns_nocache), stringResource(R.string.cs_dns_nocache_hint), m[CoreSettings.X_DNS_NOCACHE] == "true", b(CoreSettings.X_DNS_NOCACHE), divider = false) {
                    set(CoreSettings.X_DNS_NOCACHE, it.toString())
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.panel_settings_maintenance)) {
                TapRow(BoxyIcons.Power, stringResource(R.string.panel_settings_restart), stringResource(R.string.panel_settings_restart_sub), b("restart")) {
                    task("restart") { !running || BoxModule.restart() }
                }
                TapRow(BoxyIcons.ArrowCircle, stringResource(R.string.cs_pins), stringResource(R.string.cs_pins_hint), b("pins"), divider = false) {
                    task("pins") { XrayApi.clearPins() }
                }
            }
        }
        item { NoticeCard(stringResource(R.string.xray_settings_more_title), stringResource(R.string.cs_xray_more), Modifier.padding(horizontal = 2.dp)) }
    }
}

/** sing-box's settings beyond its clash_api, for the panel's Settings tab. */
internal fun LazyListScope.singBoxSettings(
    v: Map<String, String>?,
    busy: Map<String, Boolean>,
    onSet: (String, String) -> Unit,
    onClearCache: () -> Unit,
) {
    val m = v ?: return
    val b = { k: String -> busy[k] == true }
    item {
        SectionCard(stringResource(R.string.cs_section_log)) {
            ChoiceRow(BoxyIcons.Description, stringResource(R.string.xray_settings_loglevel), stringResource(R.string.cs_loglevel_hint), m.getValue(CoreSettings.S_LOGLEVEL),
                listOf("trace", "debug", "info", "warn", "error", "fatal", "panic").map { it to it }, b(CoreSettings.S_LOGLEVEL)) { onSet(CoreSettings.S_LOGLEVEL, it) }
            ToggleRow(BoxyIcons.Schedule, stringResource(R.string.cs_timestamp), stringResource(R.string.cs_timestamp_hint), m[CoreSettings.S_TIMESTAMP] == "true", b(CoreSettings.S_TIMESTAMP), divider = false) {
                onSet(CoreSettings.S_TIMESTAMP, it.toString())
            }
        }
    }
    item {
        SectionCard(stringResource(R.string.cs_section_fastest), stringResource(R.string.cs_config_note)) {
            ChoiceRow(BoxyIcons.Schedule, stringResource(R.string.xray_settings_probe_interval), stringResource(R.string.cs_probe_interval_hint), m.getValue(CoreSettings.S_URLTEST_INTERVAL),
                INTERVALS.map { it to it }, b(CoreSettings.S_URLTEST_INTERVAL)) { onSet(CoreSettings.S_URLTEST_INTERVAL, it) }
            ChoiceRow(BoxyIcons.Speed, stringResource(R.string.cs_tolerance), stringResource(R.string.cs_tolerance_hint), m.getValue(CoreSettings.S_URLTEST_TOLERANCE),
                listOf("0", "20", "50", "100", "200").map { it to "$it ms" }, b(CoreSettings.S_URLTEST_TOLERANCE)) { onSet(CoreSettings.S_URLTEST_TOLERANCE, it) }
            PresetRow(BoxyIcons.Web, stringResource(R.string.xray_settings_probe_url), stringResource(R.string.cs_probe_url_hint), m.getValue(CoreSettings.S_URLTEST_URL),
                PROBE_URLS, b(CoreSettings.S_URLTEST_URL), { if (urlOk(it)) null else "http(s)://…" }) { onSet(CoreSettings.S_URLTEST_URL, it) }
            ToggleRow(BoxyIcons.Block, stringResource(R.string.cs_block_quic), stringResource(R.string.cs_block_quic_hint), m[CoreSettings.S_BLOCK_QUIC] == "true", b(CoreSettings.S_BLOCK_QUIC), divider = false) {
                onSet(CoreSettings.S_BLOCK_QUIC, it.toString())
            }
        }
    }
    item {
        SectionCard("DNS") {
            ChoiceRow(BoxyIcons.IpAddress, stringResource(R.string.cs_dns_query), stringResource(R.string.cs_dns_query_hint), m.getValue(CoreSettings.S_DNS_STRATEGY), listOf(
                "prefer_ipv4" to stringResource(R.string.cs_ip_prefer_v4), "prefer_ipv6" to stringResource(R.string.cs_ip_prefer_v6),
                "ipv4_only" to stringResource(R.string.cs_ip_v4), "ipv6_only" to stringResource(R.string.cs_ip_v6),
            ), b(CoreSettings.S_DNS_STRATEGY)) { onSet(CoreSettings.S_DNS_STRATEGY, it) }
            ToggleRow(BoxyIcons.Dns, stringResource(R.string.cs_independent), stringResource(R.string.cs_independent_hint), m[CoreSettings.S_DNS_INDEPENDENT] == "true", b(CoreSettings.S_DNS_INDEPENDENT)) {
                onSet(CoreSettings.S_DNS_INDEPENDENT, it.toString())
            }
            ToggleRow(BoxyIcons.Save, stringResource(R.string.cs_rdrc), stringResource(R.string.cs_rdrc_hint), m[CoreSettings.S_CACHE_RDRC] == "true", b(CoreSettings.S_CACHE_RDRC), divider = false) {
                onSet(CoreSettings.S_CACHE_RDRC, it.toString())
            }
        }
    }
    item {
        // auto_detect_interface is not offered: the module sets it for its tproxy / tun mode at every start.
        SectionCard(null) {
            TapRow(BoxyIcons.Delete, stringResource(R.string.cs_clear_cache), stringResource(R.string.cs_clear_cache_hint), b("cache"), divider = false, onClick = onClearCache)
        }
    }
}

/**
 * The active core's settings as a page of Tools (next to the config files): Xray's or sing-box's, the same code the
 * panel's Settings tab shows. mihomo's run-time settings stay on the panel.
 */
@Composable
fun CoreSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by androidx.compose.runtime.produceState<com.skofqq.boxy.root.ServiceState?>(null) { value = runCatching { BoxModule.state() }.getOrNull() }
    val s = state ?: return
    val core = BoxModule.unquote(s.core)
    Column(Modifier.padding(top = contentPadding.calculateTopPadding())) {
        // The same header as every other page in Config.
        com.skofqq.boxy.ui.components.SubPageHeader(stringResource(R.string.cs_tools_title), if (core == "xray") "Xray" else "Sing-box", onBack)
        if (core == "xray") {
            XraySettingsTab(contentPadding.calculateBottomPadding(), 0, s.running)
        } else {
            var reload by remember { mutableIntStateOf(0) }
            var v by remember { mutableStateOf<Map<String, String>?>(null) }
            val busy = remember { mutableStateMapOf<String, Boolean>() }
            LaunchedEffect(reload) { v = CoreSettings.singBoxValues(context) }
            PinnedLazyPage(PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp), header = {}, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { NoticeCard("Sing-box", stringResource(R.string.cs_restart_note_sb), Modifier.padding(horizontal = 2.dp)) }
                singBoxSettings(v, busy, onSet = { key, value ->
                    if (busy[key] != true) {
                        busy[key] = true
                        scope.launch {
                            val r = CoreSettings.setSingBox(context.applicationContext, key, value)
                            busy[key] = false
                            val msg = r.exceptionOrNull()?.message?.let { if (it == "comments") context.getString(R.string.cs_comments) else it.lines().takeLast(2).joinToString(" ") }
                            com.skofqq.boxy.ui.components.BoxyToast.makeText(
                                context,
                                if (r.isSuccess) context.getString(R.string.cs_applied) else context.getString(R.string.cs_failed, msg),
                                com.skofqq.boxy.ui.components.BoxyToast.LENGTH_LONG,
                            ).show()
                            reload++
                        }
                    }
                }, onClearCache = {
                    busy["cache"] = true
                    scope.launch {
                        CoreSettings.clearSingBoxCache()
                        busy["cache"] = false
                        reload++
                    }
                })
            }
        }
    }
}

/** A link from the panel to the config's own settings in Config › Active core (the panel only runs the core). */
@Composable
internal fun ConfigSettingsLink() {
    SectionCard(null) {
        TapRow(BoxyIcons.Tune, stringResource(R.string.cs_tools_title), stringResource(R.string.panel_config_link_sub), false, divider = false) {
            com.skofqq.boxy.ui.tools.ToolsNav.request = com.skofqq.boxy.ui.tools.ROUTE_CORE_SETTINGS
            com.skofqq.boxy.ui.tools.ToolsNav.showTools = true
        }
    }
}

/** Xray's Settings tab of the panel: what acts on the running core; its config settings are a link to Config. */
@Composable
internal fun XrayPanelSettings(bottom: Dp, running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    fun task(key: String, block: suspend () -> Boolean) {
        if (busy[key] == true) return
        busy[key] = true
        scope.launch {
            val ok = block()
            busy[key] = false
            com.skofqq.boxy.ui.components.BoxyToast.makeText(context, if (ok) R.string.panel_done else R.string.op_failed, com.skofqq.boxy.ui.components.BoxyToast.LENGTH_SHORT).show()
        }
    }
    PinnedLazyPage(PaddingValues(bottom = bottom + 16.dp), header = {}, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ConfigSettingsLink() }
        item {
            SectionCard(stringResource(R.string.panel_settings_maintenance)) {
                TapRow(BoxyIcons.Power, stringResource(R.string.panel_settings_restart), stringResource(R.string.panel_settings_restart_sub), busy["restart"] == true) {
                    task("restart") { !running || BoxModule.restart() }
                }
                TapRow(BoxyIcons.ArrowCircle, stringResource(R.string.cs_pins), stringResource(R.string.cs_pins_hint), busy["pins"] == true) {
                    task("pins") { XrayApi.clearPins() }
                }
                TapRow(BoxyIcons.SystemUpdate, stringResource(R.string.panel_settings_updates), stringResource(R.string.updates_link_sub), false, divider = false) {
                    com.skofqq.boxy.ui.tools.ToolsNav.request = com.skofqq.boxy.ui.tools.ROUTE_UPDATE
                    com.skofqq.boxy.ui.tools.ToolsNav.showTools = true
                }
            }
        }
    }
}
