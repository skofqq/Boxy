package com.skofqq.boxy.ui.proxies

import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.BoxyApp
import com.skofqq.boxy.R
import com.skofqq.boxy.net.CoreApi
import com.skofqq.boxy.net.Net
import com.skofqq.boxy.ui.components.ConfirmDialog
import com.skofqq.boxy.ui.components.IconInfoRow
import com.skofqq.boxy.ui.components.InputDialog
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.components.addTaskLine
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.launch
import org.json.JSONObject

private val LOG_LEVELS = listOf("debug", "info", "warning", "error", "silent")

/** Names of the module's cores (bin_name), as the Tools page shows them. */
private val CORE_NAMES = mapOf("clash" to "Mihomo", "sing-box" to "Sing-box", "xray" to "Xray", "v2fly" to "V2Ray", "hysteria" to "Hysteria")

/**
 * Zashboard's Settings, cut down to what matters inside Boxy: the core's version and address, updates of the core,
 * subscriptions and web panel, the core's maintenance calls (reload, restart, GEO databases, caches), the switches
 * it takes at run time and the delay test.
 * Zashboard's look-and-feel settings are left out: Boxy has its own.
 */
@Composable
internal fun SettingsTab(bottom: Dp, reloadKey: Int) {
    val core = rememberCoreApi(reloadKey)
    val api = core.api
    if (core.checked && api == null) return NoCore(bottom, core.running)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = BoxyApp.instance.prefs
    var version by remember { mutableStateOf<String?>(null) }
    var configs by remember { mutableStateOf<JSONObject?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    var confirmRestart by remember { mutableStateOf(false) }
    var editUrl by remember { mutableStateOf(false) }
    var editTimeout by remember { mutableStateOf(false) }

    var webUi by remember { mutableStateOf<com.skofqq.boxy.net.Dashboard?>(null) }
    // The task dialog says "Already the latest: <name> <version>", so the task carries the core's name.
    var coreName by remember { mutableStateOf("Mihomo") }
    var dashboardSheet by remember { mutableStateOf(false) }
    var taskTitle by remember { mutableStateOf<String?>(null) }
    val taskLines = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var taskDone by remember { mutableStateOf(false) }
    var taskOk by remember { mutableStateOf(false) }

    LaunchedEffect(api, reload) {
        if (api == null) return@LaunchedEffect
        version = CoreApi.version(api)
        configs = CoreApi.configs(api)
        val bin = BoxModule.readSetting("bin_name") ?: "clash"
        webUi = com.skofqq.boxy.net.Dashboards.installed(bin)
        coreName = CORE_NAMES[bin] ?: bin
    }

    fun launchTask(title: String, cmd: String) {
        if (taskTitle != null && !taskDone) return
        taskTitle = title
        taskLines.clear()
        taskDone = false
        scope.launch {
            taskOk = com.skofqq.boxy.root.RootTask.run(cmd) { line -> taskLines.addTaskLine(line) }
            taskDone = true
        }
    }

    fun toast(ok: Boolean) = Toast.makeText(context, if (ok) R.string.panel_done else R.string.op_failed, Toast.LENGTH_SHORT).show()

    fun run(key: String, block: suspend () -> Boolean) {
        if (busy[key] == true) return
        busy[key] = true
        scope.launch {
            toast(block())
            busy[key] = false
            reload++
        }
    }

    fun patch(key: String, value: Any) {
        val a = api ?: return
        run(key) { Net.clashPatchConfigs(a, JSONObject().put(key, value)) }
    }

    val c = configs
    PinnedLazyPage(PaddingValues(bottom = bottom + 16.dp), header = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Boxy.colors.text)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (c != null) Tints.green.fg else Tints.gray.fg))
            Spacer(Modifier.width(6.dp))
            Text(api?.base?.removePrefix("http://").orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(stringResource(R.string.panel_settings_core)) {
                IconInfoRow(BoxyIcons.Core, stringResource(R.string.panel_settings_version), version ?: "-")
                IconInfoRow(BoxyIcons.NetworkMode, stringResource(R.string.panel_settings_ports), c?.let { ports(it) } ?: "-")
            }
        }
        item {
            // The module's own updaters, as on the Tools page: the core as chosen in its settings (the module restarts
            // the service afterwards), the subscriptions, and the web panel the core serves.
            val coreTitle = stringResource(R.string.panel_settings_update_core)
            val subsTitle = stringResource(R.string.panel_settings_update_subs)
            SectionCard(stringResource(R.string.panel_settings_updates)) {
                ActionRow(BoxyIcons.SystemUpdate, coreTitle, stringResource(R.string.panel_settings_update_core_sub), taskTitle == coreName && !taskDone) {
                    launchTask(coreName, "${BoxModule.SCRIPTS}/box.tool upkernel")
                }
                ActionRow(BoxyIcons.Subscriptions, subsTitle, stringResource(R.string.update_subscription_sub), taskTitle == subsTitle && !taskDone) {
                    launchTask(subsTitle, "${BoxModule.SCRIPTS}/box.tool subs")
                }
                ActionRow(
                    BoxyIcons.Language,
                    stringResource(R.string.panel_settings_update_webui),
                    webUi?.let { stringResource(R.string.panel_settings_update_webui_sub, it.title) } ?: stringResource(R.string.update_webui_sub),
                    false,
                    divider = false,
                ) { dashboardSheet = true }
            }
        }
        item {
            SectionCard(stringResource(R.string.panel_settings_maintenance)) {
                val a = api
                // sing-box's clash_api has no reload, restart, GEO databases or DNS cache: the module reloads and
                // restarts it, and the rest is left out.
                ActionRow(BoxyIcons.Refresh, stringResource(R.string.panel_settings_reload), stringResource(R.string.panel_settings_reload_sub), busy["reload"] == true) {
                    run("reload") { (a != null && core.mihomo && Net.clashReload(a)) || BoxModule.reloadConfig() }
                }
                ActionRow(BoxyIcons.Power, stringResource(R.string.panel_settings_restart), stringResource(R.string.panel_settings_restart_sub), busy["restart"] == true) {
                    confirmRestart = true
                }
                if (core.mihomo) {
                    ActionRow(BoxyIcons.Download, stringResource(R.string.panel_settings_geo), stringResource(R.string.panel_settings_geo_sub), busy["geo"] == true) {
                        if (a != null) run("geo") { CoreApi.maintain(a, CoreApi.Maintenance.UPDATE_GEO) }
                    }
                }
                ActionRow(BoxyIcons.Delete, stringResource(R.string.panel_settings_fakeip), null, busy["fakeip"] == true, divider = core.mihomo) {
                    if (a != null) run("fakeip") { CoreApi.maintain(a, CoreApi.Maintenance.FLUSH_FAKEIP) }
                }
                if (core.mihomo) {
                    ActionRow(BoxyIcons.Dns, stringResource(R.string.panel_settings_dns), null, busy["dns"] == true, divider = false) {
                        if (a != null) run("dns") { CoreApi.maintain(a, CoreApi.Maintenance.FLUSH_DNS) }
                    }
                }
            }
        }
        // These switches are mihomo's run-time settings; sing-box only takes the mode (on the Proxies tab).
        if (c != null && core.mihomo) item {
            SectionCard(stringResource(R.string.panel_settings_runtime), stringResource(R.string.panel_settings_runtime_sub)) {
                SwitchRow(BoxyIcons.Wifi, stringResource(R.string.panel_settings_allow_lan), stringResource(R.string.panel_settings_allow_lan_sub), c.optBoolean("allow-lan")) {
                    patch("allow-lan", it)
                }
                SwitchRow(BoxyIcons.Search, stringResource(R.string.panel_settings_sniffing), stringResource(R.string.panel_settings_sniffing_sub), c.optBoolean("sniffing")) {
                    patch("sniffing", it)
                }
                SwitchRow(BoxyIcons.Speed, stringResource(R.string.panel_settings_tcp_concurrent), stringResource(R.string.panel_settings_tcp_concurrent_sub), c.optBoolean("tcp-concurrent")) {
                    patch("tcp-concurrent", it)
                }
                val level = c.optString("log-level").ifBlank { "info" }
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RowBadge(BoxyIcons.Description)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.panel_settings_log_level), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
                    MenuChip(logLevelLabel(level), false, LOG_LEVELS.map { it to logLevelLabel(it) }, level) { patch("log-level", it) }
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.panel_settings_delay), stringResource(R.string.panel_settings_delay_sub)) {
                ActionRow(BoxyIcons.Link, stringResource(R.string.panel_settings_delay_url), prefs.delayUrl, false) { editUrl = true }
                ActionRow(
                    BoxyIcons.Schedule,
                    stringResource(R.string.panel_settings_delay_timeout),
                    stringResource(R.string.unit_ms, prefs.delayTimeout.toLong()),
                    false,
                    divider = false,
                ) { editTimeout = true }
            }
        }
    }

    if (dashboardSheet) {
        com.skofqq.boxy.ui.panel.DashboardSheet(onDismiss = { dashboardSheet = false; reload++ }, onInstalled = { reload++ })
    }
    taskTitle?.let { title ->
        com.skofqq.boxy.ui.components.TaskDialog(title, taskLines, taskDone, taskOk) {
            if (taskDone) {
                taskTitle = null
                reload++
            }
        }
    }
    if (confirmRestart) {
        ConfirmDialog(
            stringResource(R.string.panel_settings_restart),
            stringResource(R.string.panel_settings_restart_confirm),
            stringResource(R.string.panel_settings_restart),
            onConfirm = {
                confirmRestart = false
                val a = api
                run("restart") { if (a != null && core.mihomo) CoreApi.maintain(a, CoreApi.Maintenance.RESTART) else com.skofqq.boxy.service.BoxControl.restart(context.applicationContext) }
            },
            onDismiss = { confirmRestart = false },
        )
    }
    if (editUrl) {
        val invalid = stringResource(R.string.panel_error_url_invalid)
        InputDialog(
            stringResource(R.string.panel_settings_delay_url),
            listOf(stringResource(R.string.panel_field_url) to prefs.delayUrl),
            validate = { v -> if (v[0].startsWith("http://") || v[0].startsWith("https://")) null else invalid },
            onConfirm = { v -> prefs.updateDelayUrl(v[0].trim()); editUrl = false },
            onDismiss = { editUrl = false },
        )
    }
    if (editTimeout) {
        val invalid = stringResource(R.string.panel_settings_delay_timeout_invalid)
        InputDialog(
            stringResource(R.string.panel_settings_delay_timeout),
            listOf(stringResource(R.string.panel_settings_delay_timeout_field) to prefs.delayTimeout.toString()),
            validate = { v -> if (v[0].trim().toIntOrNull() in 500..30_000) null else invalid },
            onConfirm = { v -> prefs.updateDelayTimeout(v[0].trim().toInt()); editTimeout = false },
            onDismiss = { editTimeout = false },
        )
    }
}

/** The inbound ports the core listens on, e.g. "tproxy 9898 · redir 9797". */
private fun ports(c: JSONObject): String =
    listOf("tproxy-port" to "tproxy", "redir-port" to "redir", "mixed-port" to "mixed", "socks-port" to "socks", "port" to "http")
        .mapNotNull { (key, name) -> c.optInt(key).takeIf { it > 0 }?.let { "$name $it" } }
        .plus(if (c.optJSONObject("tun")?.optBoolean("enable") == true) listOf("tun") else emptyList())
        .joinToString(" · ")
        .ifEmpty { "-" }

@Composable
private fun logLevelLabel(level: String): String = when (level) {
    "debug" -> stringResource(R.string.core_logs_debug)
    "warning" -> stringResource(R.string.core_logs_warning)
    "error" -> stringResource(R.string.core_logs_error)
    "silent" -> stringResource(R.string.panel_settings_log_silent)
    else -> stringResource(R.string.core_logs_info)
}

@Composable
private fun RowBadge(icon: ImageVector) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Boxy.colors.surface2), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = Boxy.colors.text)
    }
}

/** A settings row that does something on a tap; a spinner stands in for the chevron while it runs. */
@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String?, busy: Boolean, divider: Boolean = true, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowBadge(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(BoxyIcons.ChevronRight, null, Modifier.size(20.dp), tint = Boxy.colors.text2.copy(alpha = 0.75f))
            }
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 76.dp, end = 18.dp), color = Boxy.colors.outline)
    }
}
