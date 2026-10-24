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
                r == ROUTE_CONFIGS -> ConfigListScreen(contentPadding, onBack = pop, onEdit = { push("$ROUTE_EDIT$it") }, onManage = { push(ROUTE_FILES + it) })
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

/** Opens a Tools page from elsewhere: set [request] to a route and switch to the Tools tab. */
object ToolsNav {
    var request by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** The config editor is open: the tab pager must not take its slanted swipes. */
    var editorOpen by androidx.compose.runtime.mutableStateOf(false)
}

@Composable
private fun ToolsHub(contentPadding: PaddingValues, navExtra: NavExtra, onOpenPage: (NavExtra) -> Unit, push: (String) -> Unit) {
    var importChooser by remember { mutableStateOf(false) }
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
PageHeader(stringResource(R.string.tools_title), stringResource(R.string.tools_subtitle))
}, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(stringResource(R.string.tools_config), stringResource(R.string.tools_config_sub)) {
                SettingsRow(BoxyIcons.Folder, stringResource(R.string.tools_row_manage), stringResource(R.string.tools_row_manage_sub)) { push(ROUTE_FILES) }
                SettingsRow(BoxyIcons.Tune, stringResource(R.string.tools_row_select), stringResource(R.string.tools_row_select_sub)) { push(ROUTE_CONFIGS) }
                SettingsRow(BoxyIcons.QrCode, stringResource(R.string.import_title), stringResource(R.string.import_row_sub)) { importChooser = true }
                SettingsRow(BoxyIcons.Subscriptions, stringResource(R.string.tools_subscription), stringResource(R.string.tools_subscription_sub), showDivider = false) { push(ROUTE_SUBSCRIPTION) }
            }
        }
        item {
            SectionCard(stringResource(R.string.tools_net_dns), stringResource(R.string.tools_net_dns_sub)) {
                SettingsRow(BoxyIcons.Wifi, stringResource(R.string.tools_row_wifi), stringResource(R.string.tools_network_sub)) { push(ROUTE_NETWORK) }
                SettingsRow(BoxyIcons.Shield, stringResource(R.string.tools_dns), stringResource(R.string.tools_dns_row_sub)) { push(ROUTE_DNS) }
                SettingsRow(BoxyIcons.Dns, stringResource(R.string.dnscrypt_servers), stringResource(R.string.dnscrypt_servers_row_sub), showDivider = false) { push(ROUTE_DNSCRYPT) }
            }
        }
        item {
            SectionCard(stringResource(R.string.tools_maint), stringResource(R.string.tools_maint_sub)) {
                SettingsRow(BoxyIcons.Download, stringResource(R.string.tools_row_update), stringResource(R.string.tools_update_row_sub)) { push(ROUTE_UPDATE) }
                SettingsRow(BoxyIcons.BarChart, stringResource(R.string.traffic_title), stringResource(R.string.traffic_row_sub)) { push(ROUTE_TRAFFIC) }
                SettingsRow(BoxyIcons.Schedule, stringResource(R.string.auto_title), stringResource(R.string.auto_row_sub), showDivider = false) { push(ROUTE_AUTOMATION) }
            }
        }
        val hidden = listOf(NavExtra.APPS, NavExtra.LOGS).filter { it != navExtra }
        if (hidden.isNotEmpty()) {
            item {
                val sectionTitle = when {
                    hidden.size > 1 -> stringResource(R.string.tools_more)
                    hidden.first() == NavExtra.APPS -> stringResource(R.string.tab_apps)
                    else -> stringResource(R.string.tab_logs)
                }
                SectionCard(sectionTitle, stringResource(R.string.tools_more_sub)) {
                    hidden.forEachIndexed { i, page ->
                        val last = i == hidden.lastIndex
                        when (page) {
                            NavExtra.APPS -> SettingsRow(BoxyIcons.Apps, stringResource(R.string.tab_apps), stringResource(R.string.tools_row_apps_sub), showDivider = !last) { onOpenPage(page) }
                            else -> SettingsRow(BoxyIcons.Description, stringResource(R.string.tab_logs), stringResource(R.string.tools_row_logs_sub), showDivider = !last) { onOpenPage(page) }
                        }
                    }
                }
            }
        }
    }
}
