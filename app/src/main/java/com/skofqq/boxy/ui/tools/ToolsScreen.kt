package com.skofqq.boxy.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.SheetGroup
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skofqq.boxy.R
import kotlinx.coroutines.launch
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.data.NavExtra
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.ui.components.PageHeader
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SettingsRow
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons

/**
 * Tools tab with its own page stack: hub → config files / file manager / editor,
 * network control, updates, subscription. Routes are strings so they survive recreation.
 */
@Composable
fun ToolsScreen(contentPadding: PaddingValues, navExtra: NavExtra, active: Boolean, onOpenPage: (NavExtra) -> Unit) {
    var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
    val push: (String) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    BackHandler(enabled = active && stack.isNotEmpty()) { pop() }

    // A page requested from another tab (the DNSCrypt row on the home page).
    LaunchedEffect(ToolsNav.request) {
        ToolsNav.request?.let { stack = listOf(it) }
        ToolsNav.request = null
    }

    val route = stack.lastOrNull() ?: ROUTE_HUB
    AnimatedContent(
        targetState = route to stack.size,
        transitionSpec = {
            val forward = targetState.second >= initialState.second
            (slideInHorizontally(tween(260)) { if (forward) it else -it / 3 }) togetherWith
                (slideOutHorizontally(tween(260)) { if (forward) -it / 3 else it })
        },
        label = "tools",
    ) { (r, _) ->
        Box(Modifier.fillMaxSize().background(Boxy.colors.page)) {
            when {
                r == ROUTE_HUB -> ToolsHub(contentPadding, navExtra, onOpenPage, push)
                r == ROUTE_CONFIGS -> ConfigListScreen(
                    contentPadding,
                    onBack = pop,
                    onEdit = { push("$ROUTE_EDIT$it") },
                    onManage = { push(ROUTE_FILES + it) },
                    onDiff = { a, b -> push("$ROUTE_DIFF$a|$b") },
                    onTools = { push(ROUTE_PROFILE_TOOLS) },
                )
                r.startsWith(ROUTE_DIFF) -> r.removePrefix(ROUTE_DIFF).split('|').let { p -> DiffScreen(contentPadding, p[0], p.getOrElse(1) { p[0] }, onBack = pop) }
                r == ROUTE_PROFILE_TOOLS -> ProfileToolsScreen(contentPadding, onBack = pop, onDiff = { a, b -> push("$ROUTE_DIFF$a|$b") })
                r.startsWith(ROUTE_FILES) -> FileManagerScreen(
                    contentPadding,
                    dir = r.removePrefix(ROUTE_FILES).ifEmpty { BoxModule.BOX_DIR },
                    onBack = pop,
                    onOpenDir = { push(ROUTE_FILES + it) },
                    onEdit = { push("$ROUTE_EDIT$it") },
                )
                r.startsWith(ROUTE_EDIT) -> EditorScreen(contentPadding, path = r.removePrefix(ROUTE_EDIT), onBack = pop)
                r == ROUTE_NETWORK -> NetworkControlScreen(contentPadding, onBack = pop)
                r == ROUTE_DNS -> DnsScreen(contentPadding, onBack = pop, onEdit = { push("$ROUTE_EDIT$it") }, onDnsServers = { push(ROUTE_DNSCRYPT) })
                r == ROUTE_DNSCRYPT -> DnsCryptServersScreen(contentPadding, onBack = pop)
                r == ROUTE_UPDATE -> UpdateScreen(contentPadding, onBack = pop)
                r == ROUTE_SUBSCRIPTION -> SubscriptionScreen(contentPadding, onBack = pop)
                r == ROUTE_TRAFFIC -> TrafficScreen(contentPadding, onBack = pop)
                r == ROUTE_AUTOMATION -> AutomationScreen(contentPadding, onBack = pop)
                r == ROUTE_PROXY_AUTH -> ProxyAuthScreen(contentPadding, onBack = pop)
                // The same screens the panel's Rules / Settings tabs open, here next to the config files.
                r == ROUTE_ROUTING -> com.skofqq.boxy.ui.proxies.XrayRoutingScreen(contentPadding, onBack = pop)
                r == ROUTE_CORE_SETTINGS -> com.skofqq.boxy.ui.proxies.CoreSettingsScreen(contentPadding, onBack = pop)
                r == ROUTE_BUILDER -> ConfigBuilderScreen(contentPadding, onBack = pop, onRouting = { push(ROUTE_ROUTING) })
            }
        }
    }
}

const val ROUTE_HUB = "hub"
const val ROUTE_CONFIGS = "configs"
const val ROUTE_FILES = "files:"
const val ROUTE_EDIT = "edit:"
const val ROUTE_NETWORK = "network"
const val ROUTE_UPDATE = "update"
const val ROUTE_SUBSCRIPTION = "subscription"
const val ROUTE_TRAFFIC = "traffic"
const val ROUTE_AUTOMATION = "automation"
const val ROUTE_DNSCRYPT = "dnscrypt"
const val ROUTE_DNS = "dns"
const val ROUTE_PROXY_AUTH = "proxy_auth"
const val ROUTE_ROUTING = "routing"
const val ROUTE_CORE_SETTINGS = "core_settings"
const val ROUTE_BUILDER = "builder"
const val ROUTE_DIFF = "diff:"
const val ROUTE_PROFILE_TOOLS = "profile-tools"

/** Opens a Tools page from elsewhere: set [request] to a route and switch to the Tools tab. */
object ToolsNav {
    var request by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** The config editor is open: the tab pager must not take its slanted swipes. */
    var editorOpen by androidx.compose.runtime.mutableStateOf(false)
    /** Asks the main screen to show the Tools tab (for a [request] made from another tab). */
    var showTools by androidx.compose.runtime.mutableStateOf(false)
}

@Composable
private fun ToolsHub(contentPadding: PaddingValues, navExtra: NavExtra, onOpenPage: (NavExtra) -> Unit, push: (String) -> Unit) {
    var importChooser by remember { mutableStateOf(false) }
    var leakCheck by remember { mutableStateOf(false) }
    var blockWebrtc by remember { mutableStateOf<Boolean?>(null) }
    var webrtcBusy by remember { mutableStateOf(false) }
    var moduleInstalled by remember { mutableStateOf(true) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        blockWebrtc = runCatching { BoxModule.blockWebrtc() }.getOrNull()
        moduleInstalled = runCatching { BoxModule.state().moduleVersion }.getOrNull() != null
    }
    if (leakCheck) LeakCheckSheet { leakCheck = false }
    var tlsPing by remember { mutableStateOf(false) }
    if (tlsPing) TlsPingSheet { tlsPing = false }
    val qr = rememberQrLaunchers()
    if (importChooser) {
        BoxySheet(stringResource(R.string.import_title), stringResource(R.string.import_chooser_sub), { importChooser = false }) {
            SheetGroup {
                SettingsRow(BoxyIcons.QrCode, stringResource(R.string.import_scan), stringResource(R.string.import_scan_sub)) { importChooser = false; qr.camera() }
                SettingsRow(BoxyIcons.Image, stringResource(R.string.import_picture), stringResource(R.string.import_picture_sub)) { importChooser = false; qr.picture() }
                SettingsRow(BoxyIcons.Link, stringResource(R.string.import_link), stringResource(R.string.import_link_sub), showDivider = false) {
                    importChooser = false
                    ImportBus.request = ImportRequest("")
                }
            }
        }
    }
    PinnedLazyPage(contentPadding, header = {
        PageHeader(stringResource(R.string.tab_config), stringResource(R.string.config_hub_sub))
    }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Ordered by how often it is used: the subscription and configs first, the rare tools last.
        item {
            SectionCard(null) {
                SettingsRow(BoxyIcons.Subscriptions, stringResource(R.string.tools_subscription), stringResource(R.string.sub_row_sub), showDivider = false) { push(ROUTE_SUBSCRIPTION) }
            }
        }
        item {
            SectionCard(stringResource(R.string.tools_config), stringResource(R.string.config_files_sub)) {
                SettingsRow(BoxyIcons.Tune, stringResource(R.string.tools_row_select), stringResource(R.string.tools_row_select_sub)) { push(ROUTE_CONFIGS) }
                SettingsRow(BoxyIcons.Folder, stringResource(R.string.tools_row_manage), stringResource(R.string.tools_row_manage_sub), showDivider = false) { push(ROUTE_FILES) }
            }
        }
        item {
            // What the active core's config holds, edited without opening the file: settings, routing, ports' auth.
            val core by androidx.compose.runtime.produceState<String?>(null) { value = runCatching { BoxModule.unquote(BoxModule.readSetting("bin_name")) }.getOrNull() }
            val hasSettings = core == "xray" || core == "sing-box"
            SectionCard(stringResource(R.string.cs_hub_title), stringResource(R.string.cs_hub_sub)) {
                if (hasSettings) SettingsRow(BoxyIcons.Settings, stringResource(R.string.cs_tools_title), stringResource(R.string.cs_tools_sub)) { push(ROUTE_CORE_SETTINGS) }
                if (core == "xray") SettingsRow(BoxyIcons.FilterList, stringResource(R.string.rt_title), stringResource(R.string.rt_edit_rules_sub)) { push(ROUTE_ROUTING) }
                // Ad blocking and merged node groups: mihomo and sing-box configs.
                if (core == "clash" || core == "sing-box") {
                    SettingsRow(BoxyIcons.Block, stringResource(R.string.profile_tools_title), stringResource(R.string.profile_tools_row_sub)) { push(ROUTE_PROFILE_TOOLS) }
                }
                SettingsRow(BoxyIcons.Lock, stringResource(R.string.proxy_auth_title), stringResource(R.string.proxy_auth_row_sub)) { push(ROUTE_PROXY_AUTH) }
                // A config of one's own (Xray): servers, groups, routing profile, without a panel template.
                SettingsRow(BoxyIcons.Add, stringResource(R.string.cb_title), stringResource(R.string.cb_row_sub), showDivider = false) { push(ROUTE_BUILDER) }
            }
        }
        item {
            SectionCard(stringResource(R.string.tools_net_dns), stringResource(R.string.tools_net_dns_sub)) {
                SettingsRow(BoxyIcons.Wifi, stringResource(R.string.tools_row_wifi), stringResource(R.string.tools_network_sub)) { push(ROUTE_NETWORK) }
                SettingsRow(BoxyIcons.Shield, stringResource(R.string.tools_dns), stringResource(R.string.tools_dns_row_sub)) { push(ROUTE_DNS) }
                com.skofqq.boxy.ui.components.SwitchRow(
                    BoxyIcons.Block,
                    stringResource(R.string.settings_block_webrtc),
                    stringResource(if (blockWebrtc == null && moduleInstalled) R.string.settings_block_webrtc_old else R.string.settings_block_webrtc_sub),
                    blockWebrtc == true,
                    enabled = blockWebrtc != null && !webrtcBusy,
                ) { on ->
                    webrtcBusy = true
                    scope.launch {
                        if (BoxModule.setBlockWebrtc(on)) blockWebrtc = on
                        webrtcBusy = false
                    }
                }
                SettingsRow(BoxyIcons.Language, stringResource(R.string.leak_title), stringResource(R.string.leak_row_sub), showDivider = false) { leakCheck = true }
            }
        }
        item {
            SectionCard(null) {
                SettingsRow(BoxyIcons.Download, stringResource(R.string.tools_row_update), stringResource(R.string.tools_update_row_sub), showDivider = false) { push(ROUTE_UPDATE) }
            }
        }
        item {
            SectionCard(stringResource(R.string.config_more), stringResource(R.string.config_more_sub)) {
                SettingsRow(BoxyIcons.Description, stringResource(R.string.tab_logs), stringResource(R.string.tools_row_logs_sub)) { onOpenPage(NavExtra.LOGS) }
                SettingsRow(BoxyIcons.BarChart, stringResource(R.string.traffic_title), stringResource(R.string.traffic_row_sub)) { push(ROUTE_TRAFFIC) }
                SettingsRow(BoxyIcons.Dns, stringResource(R.string.dnscrypt_servers), stringResource(R.string.dnscrypt_servers_row_sub)) { push(ROUTE_DNSCRYPT) }
                SettingsRow(BoxyIcons.Speed, stringResource(R.string.tls_ping_title), stringResource(R.string.tls_ping_row_sub)) { tlsPing = true }
                SettingsRow(BoxyIcons.Schedule, stringResource(R.string.auto_title), stringResource(R.string.auto_row_sub), showDivider = false) { push(ROUTE_AUTOMATION) }
            }
        }
    }
}
