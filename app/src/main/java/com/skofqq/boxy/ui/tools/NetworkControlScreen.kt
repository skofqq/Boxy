package com.skofqq.boxy.ui.tools

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.skofqq.boxy.R
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.OptionRow
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SettingsRow
import com.skofqq.boxy.ui.components.StringListEditor
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** settings.ini keys of the module's Wi‑Fi based service control (net.inotify). */
private val NET_KEYS = listOf(
    "enable_network_service_control", "use_module_on_wifi_disconnect", "use_module_on_wifi", "use_ssid_matching",
    "use_wifi_list_mode", "wifi_ssids_list", "wifi_bssids_list", "inotify_log_enabled", "mac_filter", "mac_mode", "macs_list",
    "use_sim_matching", "use_sim_list_mode", "sim_operators_list",
)

private data class NetState(
    val enabled: Boolean,
    val onDisconnect: Boolean,
    val onWifi: Boolean,
    val ssidMatching: Boolean,
    val whitelist: Boolean,
    val ssids: List<String>,
    val bssids: List<String>?,
    val log: Boolean,
    val macFilter: Boolean?,
    val macWhitelist: Boolean,
    val macs: List<String>?,
    /** Null when the installed module has no SIM rules (older than ru.3). */
    val simMatching: Boolean?,
    val simWhitelist: Boolean,
    val sims: List<String>?,
)

data class WifiNetwork(val ssid: String, val bssid: String, val rssi: Int)
data class HotspotClient(val ip: String, val mac: String, val iface: String, val name: String?)

@Composable
fun NetworkControlScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<NetState?>(null) }
    var state by remember { mutableStateOf<NetState?>(null) }
    var modeSheet by remember { mutableStateOf(false) }
    var macModeSheet by remember { mutableStateOf(false) }
    var wifiPickFor by remember { mutableStateOf<Pair<Boolean, Int>?>(null) } // (isBssid, index)
    var savedSheet by remember { mutableStateOf(false) }
    var macPickFor by remember { mutableStateOf<Int?>(null) }
    var simModeSheet by remember { mutableStateOf(false) }
    var simPickFor by remember { mutableStateOf<Int?>(null) }
    var hotspotProxy by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) { hotspotProxy = BoxModule.hotspotProxyEnabled() }
    LaunchedEffect(Unit) {
        val raw = BoxModule.readSettingsRaw(NET_KEYS)
        fun bool(k: String, d: Boolean) = BoxModule.unquote(raw[k])?.let { it == "true" } ?: d
        val s = NetState(
            enabled = bool("enable_network_service_control", false),
            onDisconnect = bool("use_module_on_wifi_disconnect", true),
            onWifi = bool("use_module_on_wifi", true),
            ssidMatching = bool("use_ssid_matching", false),
            whitelist = BoxModule.unquote(raw["use_wifi_list_mode"]) == "whitelist",
            ssids = BoxModule.parseArray(raw["wifi_ssids_list"]),
            bssids = raw["wifi_bssids_list"]?.let { BoxModule.parseArray(it) },
            log = bool("inotify_log_enabled", false),
            macFilter = raw["mac_filter"]?.let { BoxModule.unquote(it) == "true" },
            macWhitelist = BoxModule.unquote(raw["mac_mode"]) == "whitelist",
            macs = raw["macs_list"]?.let { BoxModule.parseArray(it) },
            simMatching = raw["use_sim_matching"]?.let { BoxModule.unquote(it) == "true" },
            simWhitelist = BoxModule.unquote(raw["use_sim_list_mode"]) != "blacklist",
            sims = raw["sim_operators_list"]?.let { BoxModule.parseArray(it) },
        )
        original = s
        state = s
    }

    fun save() {
        val s = state ?: return
        scope.launch {
            val writes = buildList {
                add("enable_network_service_control" to s.enabled.toString())
                add("use_module_on_wifi_disconnect" to s.onDisconnect.toString())
                add("use_module_on_wifi" to s.onWifi.toString())
                add("use_ssid_matching" to s.ssidMatching.toString())
                add("use_wifi_list_mode" to if (s.whitelist) "whitelist" else "blacklist")
                add("inotify_log_enabled" to s.log.toString())
                if (s.simMatching != null) {
                    add("use_sim_matching" to s.simMatching.toString())
                    add("use_sim_list_mode" to if (s.simWhitelist) "whitelist" else "blacklist")
                }
                if (s.macFilter != null) {
                    add("mac_filter" to s.macFilter.toString())
                    add("mac_mode" to if (s.macWhitelist) "whitelist" else "blacklist")
                }
            }
            var ok = writes.all { (k, v) -> BoxModule.writeSetting(k, v) }
            ok = ok && BoxModule.writeSettingRaw("wifi_ssids_list", BoxModule.toArray(s.ssids.filter { it.isNotBlank() }))
            if (s.bssids != null) ok = ok && BoxModule.writeSettingRaw("wifi_bssids_list", BoxModule.toArray(s.bssids.filter { it.isNotBlank() }))
            if (s.macs != null) ok = ok && BoxModule.writeSettingRaw("macs_list", BoxModule.toArray(s.macs.filter { it.isNotBlank() }))
            if (s.sims != null) ok = ok && BoxModule.writeSettingRaw("sim_operators_list", BoxModule.toArray(s.sims.filter { it.isNotBlank() }))
            if (ok) original = s
            Toast.makeText(context, if (ok) R.string.saved else R.string.net_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        PinnedLazyPage(contentPadding, header = {
SubPageHeader(stringResource(R.string.tools_network), stringResource(R.string.tools_network_sub), onBack)
}, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val s = state
            if (s == null) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                return@PinnedLazyPage
            }
            item {
                SectionCard(stringResource(R.string.net_general), stringResource(R.string.net_general_sub)) {
                    SwitchRow(BoxyIcons.Tune, stringResource(R.string.net_enable), stringResource(R.string.net_enable_sub), s.enabled) { state = s.copy(enabled = it) }
                    SwitchRow(BoxyIcons.Wifi, stringResource(R.string.net_on_wifi), stringResource(R.string.net_on_wifi_sub), s.onWifi, enabled = s.enabled) { state = s.copy(onWifi = it) }
                    SwitchRow(BoxyIcons.Router, stringResource(R.string.net_on_disconnect), stringResource(R.string.net_on_disconnect_sub), s.onDisconnect, enabled = s.enabled) { state = s.copy(onDisconnect = it) }
                    SwitchRow(BoxyIcons.Check, stringResource(R.string.net_ssid_matching), stringResource(R.string.net_ssid_matching_sub), s.ssidMatching, enabled = s.enabled) { state = s.copy(ssidMatching = it) }
                    SettingsRow(
                        if (s.whitelist) BoxyIcons.CheckCircle else BoxyIcons.Block,
                        stringResource(R.string.net_list_mode),
                        stringResource(if (s.whitelist) R.string.net_mode_whitelist_sub else R.string.net_mode_blacklist_sub),
                    ) { modeSheet = true }
                    SwitchRow(BoxyIcons.Description, stringResource(R.string.net_log), stringResource(R.string.net_log_sub), s.log, showDivider = false) { state = s.copy(log = it) }
                }
            }
            item {
                SectionCard(stringResource(R.string.net_ssid_list), stringResource(R.string.net_ssid_list_sub)) {
                    StringListEditor(
                        s.ssids.ifEmpty { listOf("") },
                        { state = s.copy(ssids = it) },
                        stringResource(R.string.net_hint_ssid),
                        onPick = { wifiPickFor = false to it },
                        pickIcon = BoxyIcons.Wifi,
                        extra = Triple(stringResource(R.string.net_saved_title), stringResource(R.string.net_saved_sub), BoxyIcons.Wifi),
                        onExtra = { savedSheet = true },
                    )
                }
            }
            if (s.bssids != null) {
                item {
                    SectionCard(stringResource(R.string.net_bssid_list), stringResource(R.string.net_bssid_list_sub)) {
                        StringListEditor(
                            s.bssids.ifEmpty { listOf("") },
                            { state = s.copy(bssids = it) },
                            stringResource(R.string.net_hint_bssid),
                            onPick = { wifiPickFor = true to it },
                            pickIcon = BoxyIcons.Router,
                        )
                    }
                }
            }
            if (s.simMatching != null && s.sims != null) {
                item {
                    SectionCard(stringResource(R.string.net_sim_title), stringResource(R.string.net_sim_sub)) {
                        SwitchRow(
                            BoxyIcons.SimCard,
                            stringResource(R.string.net_sim_matching),
                            stringResource(R.string.net_sim_matching_sub),
                            s.simMatching,
                            enabled = s.enabled && s.onDisconnect,
                        ) { state = s.copy(simMatching = it) }
                        SettingsRow(
                            if (s.simWhitelist) BoxyIcons.CheckCircle else BoxyIcons.Block,
                            stringResource(R.string.net_sim_list_mode),
                            stringResource(if (s.simWhitelist) R.string.net_sim_whitelist_sub else R.string.net_sim_blacklist_sub),
                            showDivider = false,
                        ) { simModeSheet = true }
                        Spacer(Modifier.height(8.dp))
                        StringListEditor(
                            s.sims.ifEmpty { listOf("") },
                            { state = s.copy(sims = it) },
                            stringResource(R.string.net_hint_sim),
                            onPick = { simPickFor = it },
                            pickIcon = BoxyIcons.SimCard,
                        )
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.net_hotspot_title), stringResource(R.string.net_hotspot_sub)) {
                    SwitchRow(
                        BoxyIcons.Hotspot,
                        stringResource(R.string.net_hotspot_proxy),
                        stringResource(if (hotspotProxy != false) R.string.net_hotspot_proxy_on else R.string.net_hotspot_proxy_off),
                        checked = hotspotProxy == true,
                        enabled = hotspotProxy != null,
                        showDivider = false,
                    ) { on ->
                        scope.launch {
                            if (BoxModule.setHotspotProxy(on)) {
                                hotspotProxy = on
                                Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, R.string.net_save_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            if (s.macFilter != null && s.macs != null && hotspotProxy != false) {
                item {
                    SectionCard(stringResource(R.string.net_mac_title), stringResource(R.string.net_mac_sub)) {
                        SwitchRow(BoxyIcons.Hotspot, stringResource(R.string.net_mac_enable), stringResource(R.string.net_mac_enable_sub), s.macFilter) { state = s.copy(macFilter = it) }
                        SettingsRow(
                            if (s.macWhitelist) BoxyIcons.CheckCircle else BoxyIcons.Block,
                            stringResource(if (s.macWhitelist) R.string.net_mac_whitelist else R.string.net_mac_blacklist),
                            stringResource(if (s.macWhitelist) R.string.net_mac_whitelist_sub else R.string.net_mac_blacklist_sub),
                            showDivider = false,
                        ) { macModeSheet = true }
                        Spacer(Modifier.height(8.dp))
                        StringListEditor(
                            s.macs.ifEmpty { listOf("") },
                            { state = s.copy(macs = it) },
                            stringResource(R.string.net_hint_mac),
                            onPick = { macPickFor = it },
                            pickIcon = BoxyIcons.Hotspot,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state != null && state != original,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(contentPadding).padding(bottom = 12.dp),
        ) {
            SaveButton { save() }
        }
    }

    val s = state
    if (modeSheet && s != null) {
        BoxySheet(stringResource(R.string.net_list_mode), stringResource(R.string.net_list_mode_sub), { modeSheet = false }) {
            OptionRow(stringResource(R.string.net_mode_whitelist), stringResource(R.string.net_mode_whitelist_sub), s.whitelist) { state = s.copy(whitelist = true); modeSheet = false }
            OptionRow(stringResource(R.string.net_mode_blacklist), stringResource(R.string.net_mode_blacklist_sub), !s.whitelist) { state = s.copy(whitelist = false); modeSheet = false }
        }
    }
    if (macModeSheet && s != null) {
        BoxySheet(stringResource(R.string.net_mac_title), null, { macModeSheet = false }) {
            OptionRow(stringResource(R.string.net_mac_whitelist), stringResource(R.string.net_mac_whitelist_sub), s.macWhitelist) { state = s.copy(macWhitelist = true); macModeSheet = false }
            OptionRow(stringResource(R.string.net_mac_blacklist), stringResource(R.string.net_mac_blacklist_sub), !s.macWhitelist) { state = s.copy(macWhitelist = false); macModeSheet = false }
        }
    }
    if (simModeSheet && s != null) {
        BoxySheet(stringResource(R.string.net_sim_list_mode), null, { simModeSheet = false }) {
            OptionRow(stringResource(R.string.net_mode_whitelist), stringResource(R.string.net_sim_whitelist_sub), s.simWhitelist) { state = s.copy(simWhitelist = true); simModeSheet = false }
            OptionRow(stringResource(R.string.net_mode_blacklist), stringResource(R.string.net_sim_blacklist_sub), !s.simWhitelist) { state = s.copy(simWhitelist = false); simModeSheet = false }
        }
    }
    simPickFor?.let { index ->
        SimPickerSheet(onDismiss = { simPickFor = null }) { value ->
            val cur = state ?: return@SimPickerSheet
            state = cur.copy(sims = (cur.sims ?: emptyList()).ifEmpty { listOf("") }.toMutableList().also { it[index.coerceAtMost(it.lastIndex)] = value })
            simPickFor = null
        }
    }
    wifiPickFor?.let { (isBssid, index) ->
        WifiPickerSheet(onDismiss = { wifiPickFor = null }) { net ->
            val cur = state ?: return@WifiPickerSheet
            state = if (isBssid) {
                cur.copy(bssids = (cur.bssids ?: emptyList()).ifEmpty { listOf("") }.toMutableList().also { it[index.coerceAtMost(it.lastIndex)] = net.bssid })
            } else {
                cur.copy(ssids = cur.ssids.ifEmpty { listOf("") }.toMutableList().also { it[index.coerceAtMost(it.lastIndex)] = net.ssid })
            }
            wifiPickFor = null
        }
    }
    if (savedSheet) {
        val cur = state
        SavedWifiSheet(cur?.ssids.orEmpty(), onDismiss = { savedSheet = false }) { picked ->
            val s = state ?: return@SavedWifiSheet
            val kept = s.ssids.filter { it.isNotBlank() }
            state = s.copy(ssids = kept + picked.filter { it !in kept })
            savedSheet = false
        }
    }
    macPickFor?.let { index ->
        HotspotPickerSheet(onDismiss = { macPickFor = null }) { client ->
            val cur = state ?: return@HotspotPickerSheet
            state = cur.copy(macs = (cur.macs ?: emptyList()).ifEmpty { listOf("") }.toMutableList().also { it[index.coerceAtMost(it.lastIndex)] = client.mac })
            macPickFor = null
        }
    }
}

@Composable
fun SaveButton(onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.accent).clickable(onClick = onClick).padding(horizontal = 26.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BoxyIcons.Save, null, Modifier.size(20.dp), tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.action_save), color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

/** Nearby Wi‑Fi from the system scanner (root `cmd wifi`, no location permission needed). */
@Composable
private fun WifiPickerSheet(onDismiss: () -> Unit, onPick: (WifiNetwork) -> Unit) {
    var list by remember { mutableStateOf<List<WifiNetwork>?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            BoxModule.exec("cmd wifi start-scan")
            delay(2500)
            val (ok, out) = BoxModule.exec("cmd wifi list-scan-results")
            if (!ok) return@withContext null
            // Columns: BSSID  Frequency  RSSI  Age(sec)  SSID  Flags
            out.drop(1).mapNotNull { line ->
                val m = Regex("^\\s*([0-9a-fA-F:]{17})\\s+\\d+\\s+(-?\\d+)\\s+[\\d.>]+\\s+(.*?)\\s+(\\[.*)?$").find(line) ?: return@mapNotNull null
                val ssid = m.groupValues[3].trim()
                WifiNetwork(ssid, m.groupValues[1].lowercase(), m.groupValues[2].toIntOrNull() ?: -100)
            }.filter { it.ssid.isNotBlank() }.sortedByDescending { it.rssi }.distinctBy { it.bssid }
        }
        if (result == null) error = true else list = result
    }
    BoxySheet(stringResource(R.string.net_wifi_picker_title), null, onDismiss) {
        val l = list
        when {
            error -> Text(stringResource(R.string.net_wifi_picker_error), color = Boxy.colors.text2)
            l == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.net_wifi_picker_loading), color = Boxy.colors.text2)
            }
            l.isEmpty() -> Text(stringResource(R.string.net_wifi_picker_empty), color = Boxy.colors.text2)
            else -> l.forEach { n ->
                PickRow(n.ssid, "${n.bssid} · ${n.rssi} dBm") { onPick(n) }
            }
        }
    }
}

/**
 * Wi-Fi networks saved on the phone (the ones it has connected to), several at once. Only the network names
 * are read from the system Wi-Fi store with root; passwords are never read.
 */
@Composable
private fun SavedWifiSheet(current: List<String>, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var list by remember { mutableStateOf<List<String>?>(null) }
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        list = withContext(Dispatchers.IO) {
            val (_, out) = BoxModule.exec(
                "for f in /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml /data/misc/wifi/WifiConfigStore.xml; do " +
                    "[ -f \"\$f\" ] && grep -o '<string name=\"SSID\">[^<]*' \"\$f\" && break; done",
            )
            out.mapNotNull { line ->
                val raw = line.substringAfter(">", "")
                val name = raw.replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'")
                    .trim().removeSurrounding("\"")
                name.takeIf { it.isNotBlank() }
            }.distinct().sortedBy { it.lowercase() }
        }
    }
    BoxySheet(stringResource(R.string.net_saved_title), stringResource(R.string.net_saved_sheet_sub), onDismiss) {
        val l = list
        when {
            l == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.net_wifi_picker_loading), color = Boxy.colors.text2)
            }
            l.isEmpty() -> Text(stringResource(R.string.net_saved_empty), color = Boxy.colors.text2)
            else -> {
                l.forEach { ssid ->
                    val already = ssid in current
                    val on = already || ssid in picked
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (on) Boxy.colors.accent.copy(alpha = 0.14f) else Boxy.colors.card)
                            .then(if (already) Modifier else Modifier.clickable { if (ssid in picked) picked.remove(ssid) else picked.add(ssid) })
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(BoxyIcons.Wifi, null, Modifier.size(20.dp), tint = if (on) Boxy.colors.accent else Boxy.colors.text2)
                        Spacer(Modifier.width(12.dp))
                        Text(ssid, Modifier.weight(1f), color = Boxy.colors.text, maxLines = 1)
                        if (already) {
                            Text(stringResource(R.string.net_saved_added), style = MaterialTheme.typography.labelMedium, color = Boxy.colors.text2)
                        } else if (on) {
                            Icon(BoxyIcons.Check, null, Modifier.size(20.dp), tint = Boxy.colors.accent)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                com.skofqq.boxy.ui.components.SheetButtons(
                    if (picked.isEmpty()) stringResource(R.string.action_cancel) else stringResource(R.string.net_saved_add, picked.size),
                    { if (picked.isEmpty()) onDismiss() else onAdd(picked.toList()) },
                )
            }
        }
    }
}

/** Devices connected to this phone's hotspot (neighbour table of the AP interfaces). */
@Composable
private fun HotspotPickerSheet(onDismiss: () -> Unit, onPick: (HotspotClient) -> Unit) {
    var list by remember { mutableStateOf<List<HotspotClient>?>(null) }
    LaunchedEffect(Unit) {
        list = withContext(Dispatchers.IO) {
            val (_, neigh) = BoxModule.exec("ip neigh show")
            val (_, leases) = BoxModule.exec("cat /data/misc/dhcp/dnsmasq.leases 2>/dev/null")
            val names = leases.mapNotNull { l -> l.split(' ').takeIf { it.size >= 4 }?.let { it[1].lowercase() to it[3] } }.toMap()
            neigh.mapNotNull { line ->
                val m = Regex("^(\\S+) dev (\\S+) lladdr ([0-9a-fA-F:]{17})").find(line) ?: return@mapNotNull null
                val iface = m.groupValues[2]
                if (!(iface.startsWith("wlan") && iface != "wlan0") && !iface.startsWith("ap") && !iface.startsWith("swlan") &&
                    !iface.startsWith("softap") && !iface.startsWith("rndis") && !iface.startsWith("ncm")
                ) {
                    return@mapNotNull null
                }
                val mac = m.groupValues[3].lowercase()
                HotspotClient(m.groupValues[1], mac, iface, names[mac]?.takeIf { it != "*" })
            }.distinctBy { it.mac }
        }
    }
    BoxySheet(stringResource(R.string.net_mac_picker_title), null, onDismiss) {
        val l = list
        when {
            l == null -> Text(stringResource(R.string.net_mac_picker_loading), color = Boxy.colors.text2)
            l.isEmpty() -> Text(stringResource(R.string.net_mac_picker_empty), color = Boxy.colors.text2)
            else -> l.forEach { c -> PickRow(c.name ?: c.ip, "${c.mac} · ${c.iface}") { onPick(c) } }
        }
    }
}

/** Operator names and MCC+MNC codes of the SIM cards in the phone. */
@Composable
private fun SimPickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var list by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(Unit) {
        list = withContext(Dispatchers.IO) {
            suspend fun prop(name: String) = BoxModule.exec("getprop $name").second.firstOrNull().orEmpty().split(',')
            val names = prop("gsm.sim.operator.alpha")
            val codes = prop("gsm.sim.operator.numeric")
            names.indices.flatMap { i ->
                val name = names[i].trim()
                val code = codes.getOrNull(i)?.trim().orEmpty()
                listOfNotNull(name.takeIf { it.isNotEmpty() }?.let { it to "SIM ${i + 1} · $code" }, code.takeIf { it.isNotEmpty() }?.let { it to "SIM ${i + 1} · $name" })
            }.distinctBy { it.first }
        }
    }
    BoxySheet(stringResource(R.string.net_sim_picker_title), null, onDismiss) {
        val l = list
        when {
            l == null -> Text(stringResource(R.string.net_mac_picker_loading), color = Boxy.colors.text2)
            l.isEmpty() -> Text(stringResource(R.string.net_sim_picker_empty), color = Boxy.colors.text2)
            else -> l.forEach { (value, sub) -> PickRow(value, sub) { onPick(value) } }
        }
    }
}

@Composable
private fun PickRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(Boxy.colors.card).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
    }
}
